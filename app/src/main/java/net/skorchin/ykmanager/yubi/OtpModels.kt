package net.skorchin.ykmanager.yubi

import android.content.Context
import androidx.core.content.edit
import com.yubico.yubikit.yubiotp.Slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

// Data types shared by the OTP slot features (OtpFeature, OtpNdef, OtpSlotProgrammer,
// OtpOutputUpdater) and the screens that show them.

/** The type of credential stored in an OTP slot. */
enum class SlotType(val label: String) {
    YUBICO_OTP("Yubico OTP"),
    HOTP("OATH-HOTP"),
    STATIC("Static password"),
    CHALRESP("Challenge-response (HMAC-SHA1)"),
    CHALRESP_OTHER("Challenge-response (not HMAC-SHA1)"),
    KEYBOARD_UNKNOWN("Keyboard output (type not identified yet)"),
}

/** How we know a slot's type. */
enum class Evidence(val label: String) {
    HARDWARE("reported by the YubiKey"),
    OUTPUT("identified from the slot's output"),
    PROBE("verified by a challenge-response test"),
    HISTORY("recorded when programmed by this app"),
}

/** Everything the app knows about one slot. */
data class SlotView(
    val slot: Slot,
    val configured: Boolean?,          // null = firmware too old to tell
    val touchTriggered: Boolean?,      // null = unknown / not configured
    val type: SlotType?,
    val evidence: Evidence?,
    val details: List<Pair<String, String>>,
    val history: SlotHistory?,
)

/** Metadata (never secrets) about a slot this app programmed. */
data class SlotHistory(
    val type: SlotType,
    val programmedAt: Long,
    val details: Map<String, String>,
) {
    fun toJson(): String = JSONObject().apply {
        put("type", type.name)
        put("at", programmedAt)
        put("details", JSONObject(details as Map<*, *>))
    }.toString()

    companion object {

        fun fromJson(s: String): SlotHistory? = runCatching {
            val o = JSONObject(s)
            val d = o.getJSONObject("details")
            SlotHistory(
                SlotType.valueOf(o.getString("type")),
                o.getLong("at"),
                d.keys().asSequence().associateWith { d.getString(it) },
            )
        }.getOrNull()
    }
}

/** Result of identifying a slot from its output. */
data class Identification(val type: SlotType, val evidence: Evidence, val details: List<Pair<String, String>>)

/** User-entered parameters for programming a slot. */
sealed interface SlotProgram {
    data class YubicoOtp(
        val publicIdModhex: String?,   // null = random / serial-based
        val useSerialAsPublicId: Boolean,
        val privateIdHex: String?,     // null = random
        val keyHex: String?,           // null = random
    ) : SlotProgram

    data class Hotp(val secretBase32: String, val digits8: Boolean, val initialCounter: Int) : SlotProgram
    data class StaticPassword(
        val password: String?,       // null = generate randomly
        val generateLength: Int,
        val modhexOnly: Boolean = false,   // layout-independent characters only
    ) : SlotProgram
    data class ChallengeResponse(val secretHex: String?, val requireTouch: Boolean) : SlotProgram
}

/** Options shared by all slot writes. */
data class SlotWriteOptions(
    val appendEnter: Boolean = true,
    val currentAccessCodeHex: String = "",
    val newAccessCodeHex: String = "",
    /** Output/device flags (Settings → OTP output). [appendEnter] overrides its Enter choice. */
    val output: OtpOutputSettings = OtpOutputSettings(),
) {
    val effectiveOutput: OtpOutputSettings get() = output.copy(appendEnter = appendEnter)
}

class SlotHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("slot_history", Context.MODE_PRIVATE)

    private fun key(serial: Int?, slot: Slot) = "${serial ?: "unknown"}:${slot.name}"

    fun get(serial: Int?, slot: Slot): SlotHistory? =
        prefs.getString(key(serial, slot), null)?.let(SlotHistory::fromJson)

    fun put(serial: Int?, slot: Slot, h: SlotHistory?) {
        prefs.edit {
            if (h == null) remove(key(serial, slot)) else putString(key(serial, slot), h.toJson())
        }
    }

    fun swap(serial: Int?) {
        val one = get(serial, Slot.ONE)
        val two = get(serial, Slot.TWO)
        put(serial, Slot.ONE, two)
        put(serial, Slot.TWO, one)
    }
}

/**
 * What the app currently knows about both OTP slots of the connected key: the hardware
 * state, results of identifications made this session, and the local history of slots this
 * app programmed. Shared by the OTP feature classes; [slots] is what the screens observe.
 */
class OtpSlotState(private val core: YubiKeyCore, context: Context) {

    val history = SlotHistoryStore(context)

    /** Identification results gathered this session, keyed by serial + slot. */
    private val identified = mutableMapOf<String, Identification>()

    private val _slots = MutableStateFlow<List<SlotView>>(emptyList())
    val slots: StateFlow<List<SlotView>> = _slots.asStateFlow()

    val serial: Int? get() = core.keyInfo.value?.serial

    private fun idKey(serial: Int?, slot: Slot) = "${serial ?: "unknown"}:${slot.name}"

    fun identified(serial: Int?, slot: Slot, ident: Identification) { identified[idKey(serial, slot)] = ident }

    fun forgetIdentification(serial: Int?, slot: Slot) { identified.remove(idKey(serial, slot)) }

    /** The slot was programmed or deleted: replace its history and drop what was identified. */
    fun recordWrite(serial: Int?, slot: Slot, h: SlotHistory?) {
        history.put(serial, slot, h)
        forgetIdentification(serial, slot)
    }

    /** The key swapped its two slots. */
    fun swapSlots(serial: Int?) {
        history.swap(serial)
        val a = identified.remove(idKey(serial, Slot.ONE))
        val b = identified.remove(idKey(serial, Slot.TWO))
        a?.let { identified[idKey(serial, Slot.TWO)] = it }
        b?.let { identified[idKey(serial, Slot.ONE)] = it }
    }

    /** Rebuild slot views from the latest hardware state. */
    fun rebuild() {
        val info = core.keyInfo.value
        val state = info?.otpState
        val serial = info?.serial
        _slots.value = listOf(Slot.ONE, Slot.TWO).map { slot ->
            val configured = state?.let { runCatching { it.isConfigured(slot) }.getOrNull() }
            val touch = if (configured == true) {
                state.let { runCatching { it.isTouchTriggered(slot) }.getOrNull() }
            } else null
            val hist = if (configured == false) null else history.get(serial, slot)
            val ident = if (configured == false) null else identified[idKey(serial, slot)]
            slotView(slot, configured, touch, hist, ident)
        }
    }

    companion object {
        /** Combine what is known about one slot, preferring the strongest evidence. */
        fun slotView(slot: Slot, configured: Boolean?, touch: Boolean?, hist: SlotHistory?, ident: Identification?): SlotView {
            var type: SlotType? = null
            var evidence: Evidence? = null
            val details = mutableListOf<Pair<String, String>>()
            when {
                ident != null -> {
                    type = ident.type; evidence = ident.evidence; details += ident.details
                }
                hist != null && consistent(hist.type, touch) -> {
                    type = hist.type; evidence = Evidence.HISTORY
                }
                configured == true && touch == true -> {
                    type = SlotType.KEYBOARD_UNKNOWN; evidence = Evidence.HARDWARE
                }
                configured == true && touch == false -> {
                    type = SlotType.CHALRESP; evidence = Evidence.HARDWARE
                }
            }
            return SlotView(slot, configured, touch, type, evidence, details, hist)
        }

        /** Whether a recorded [t] fits the slot's touch-triggered flag (challenge-response slots aren't). */
        fun consistent(t: SlotType, touch: Boolean?): Boolean = when (touch) {
            null -> true
            true -> t != SlotType.CHALRESP && t != SlotType.CHALRESP_OTHER
            false -> t == SlotType.CHALRESP || t == SlotType.CHALRESP_OTHER
        }

        fun formatTime(t: Long): String = DateFormat.getDateTimeInstance().format(Date(t))
    }
}

/** The word used for a slot's credential in its output pattern ("public ID + OTP + Enter"). */
val SlotType.outputPart: String
    get() = when (this) {
        SlotType.YUBICO_OTP -> "OTP"
        SlotType.HOTP -> "code"
        SlotType.STATIC -> "password"
        else -> "output"
    }

val Slot.num: Int get() = if (this == Slot.ONE) 1 else 2

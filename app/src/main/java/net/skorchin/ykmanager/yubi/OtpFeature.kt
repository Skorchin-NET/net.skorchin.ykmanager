package net.skorchin.ykmanager.yubi

import android.content.Context
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.yubiotp.Slot
import kotlinx.coroutines.flow.StateFlow

/**
 * The OTP application, as used by the OTP slots screen, recipes and the inventory.
 *
 * This class keeps the public API stable and handles slot identification; the work is split
 * across focused classes that share one [OtpSlotState]:
 *  - [OtpNdef]: NFC (NDEF) tag reading and decoding, and pointing the tag at a slot
 *  - [OtpSlotProgrammer]: programming, deleting and swapping slots, HMAC challenge-response
 *  - [OtpOutputUpdater]: changing the output settings of a programmed slot
 *  - [OtpCodec]: pure encoding/decoding (output classification, access codes, NDEF config)
 */
class OtpFeature(private val core: YubiKeyCore, context: Context) {

    private val state = OtpSlotState(core, context)
    val ndef = OtpNdef(core, state, context)
    val programmer = OtpSlotProgrammer(core, state)
    val outputs = OtpOutputUpdater(core, state)

    val slots: StateFlow<List<SlotView>> get() = state.slots

    /** Rebuild slot views from the latest hardware state. */
    fun rebuild() = state.rebuild()

    // ------------------------------------------------------------------
    // Identification
    // ------------------------------------------------------------------

    /** Classify text typed by the YubiKey when a slot is triggered. */
    fun identifyFromOutput(slot: Slot, rawOutput: String) {
        val ident = OtpCodec.classifyOutput(rawOutput, state.serial)
        if (ident == null) {
            core.error("Nothing was captured. Touch the YubiKey while the field is focused.")
            return
        }
        state.identified(state.serial, slot, ident)
        rebuild()
        core.toast("Slot ${slot.num}: ${ident.type.label}")
    }

    /** For non-touch slots: send a challenge to see if the slot answers HMAC-SHA1. */
    fun probeChallengeResponse(slot: Slot) {
        core.run("test slot ${slot.num}") { device ->
            val challenge = Encoding.randomBytes(32)
            val result = runCatching {
                core.showTouchPrompt(true)
                core.otp(device) { it.calculateHmacSha1(slot, challenge, core.touchState()) }
            }
            core.showTouchPrompt(false)
            state.identified(state.serial, slot, result.fold(
                onSuccess = { resp ->
                    Identification(
                        SlotType.CHALRESP, Evidence.PROBE,
                        listOf("Test response" to Encoding.toHex(resp).take(16) + "…")
                    )
                },
                onFailure = {
                    Identification(
                        SlotType.CHALRESP_OTHER, Evidence.PROBE,
                        listOf("HMAC test" to "no valid response (likely Yubico OTP challenge-response)")
                    )
                },
            ))
        }
    }

    // ------------------------------------------------------------------
    // NFC (NDEF) tag - see OtpNdef
    // ------------------------------------------------------------------

    fun knownNdefPrefixes(): Set<String> = ndef.knownNdefPrefixes()
    fun ndefSlot(serial: Int?): Slot = ndef.ndefSlot(serial)
    fun ndefSlotKnown(serial: Int?): Boolean = ndef.ndefSlotKnown(serial)
    fun identifyViaNdef(slot: Slot, accessCodeHex: String = "", restore: Boolean = true) =
        ndef.identifyViaNdef(slot, accessCodeHex, restore)
    fun readNdefOtp(onResult: (String) -> Unit) = ndef.readNdefOtp(onResult)
    fun showNdefOutput() = ndef.showNdefOutput()
    fun setNdef(slot: Slot, prefix: String, asText: Boolean, accessCodeHex: String) =
        ndef.setNdef(slot, prefix, asText, accessCodeHex)

    // ------------------------------------------------------------------
    // Slot programming - see OtpSlotProgrammer and OtpOutputUpdater
    // ------------------------------------------------------------------

    fun program(slot: Slot, program: SlotProgram, opts: SlotWriteOptions) = programmer.program(slot, program, opts)
    suspend fun programOn(device: YubiKeyDevice, slot: Slot, program: SlotProgram, opts: SlotWriteOptions, serial: Int?) =
        programmer.programOn(device, slot, program, opts, serial)
    suspend fun deleteOn(device: YubiKeyDevice, slot: Slot, accessCodeHex: String, serial: Int?) =
        programmer.deleteOn(device, slot, accessCodeHex, serial)
    fun delete(slot: Slot, accessCodeHex: String) = programmer.delete(slot, accessCodeHex)
    fun deleteBoth() = programmer.deleteBoth()
    fun swap() = programmer.swap()
    fun calculateHmac(slot: Slot, challengeHex: String) = programmer.calculateHmac(slot, challengeHex)
    fun updateOutput(slot: Slot, output: OtpOutputSettings, accessCodeHex: String) =
        outputs.updateOutput(slot, output, accessCodeHex)

    companion object {
        fun classifyOutput(raw: String, serial: Int?): Identification? = OtpCodec.classifyOutput(raw, serial)
        fun formatTime(t: Long): String = OtpSlotState.formatTime(t)
    }
}

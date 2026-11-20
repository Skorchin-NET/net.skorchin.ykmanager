package net.skorchin.ykmanager.yubi

import android.content.Context
import androidx.core.content.edit
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.otp.OtpConnection
import com.yubico.yubikit.core.smartcard.Apdu
import com.yubico.yubikit.core.smartcard.ApduException
import com.yubico.yubikit.core.smartcard.AppId
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.core.smartcard.SmartCardProtocol
import com.yubico.yubikit.yubiotp.Slot

/**
 * The OTP application's NFC (NDEF) tag: reading and decoding what the tag sends,
 * pointing it at a slot, and the per-key memory of which slot and prefixes this app set.
 */
class OtpNdef(private val core: YubiKeyCore, private val state: OtpSlotState, context: Context) {

    /** NDEF prefixes this app has written, so a read can split prefix from slot output exactly. */
    private val ndefPrefs = context.getSharedPreferences("ndef_prefixes", Context.MODE_PRIVATE)

    fun knownNdefPrefixes(): Set<String> = ndefPrefs.getStringSet("prefixes", emptySet()).orEmpty()

    private fun rememberNdefPrefix(prefix: String) {
        if (prefix.isEmpty()) return
        val set = (knownNdefPrefixes() + prefix).toList().takeLast(20).toSet()
        ndefPrefs.edit { putStringSet("prefixes", set) }
    }

    /**
     * The slot the key's NFC (NDEF) tag sends. A YubiKey can't report this, so it is
     * remembered per serial whenever this app sets it; until then it is assumed to be the
     * factory default, slot 1.
     */
    fun ndefSlot(serial: Int?): Slot =
        if (ndefPrefs.getString(ndefSlotKey(serial), null) == Slot.TWO.name) Slot.TWO else Slot.ONE

    /** True when [ndefSlot] was set by this app rather than assumed. */
    fun ndefSlotKnown(serial: Int?): Boolean = ndefPrefs.contains(ndefSlotKey(serial))

    private fun rememberNdefSlot(serial: Int?, slot: Slot) {
        ndefPrefs.edit { putString(ndefSlotKey(serial), slot.name) }
        state.rebuild()
    }

    private fun ndefSlotKey(serial: Int?) = "slot_${serial ?: "unknown"}"

    /**
     * Over NFC, read the NDEF message, which carries the output of the NDEF slot only. For the
     * other slot the NFC output is pointed at it for the read (same prefix and payload type,
     * learned from a first read), then, if [restore], pointed back - all within one tap.
     */
    fun identifyViaNdef(slot: Slot, accessCodeHex: String = "", restore: Boolean = true) {
        core.run("read the NFC (NDEF) output") { device ->
            if (device !is NfcYubiKeyDevice) throw UserFacingException("Reading NDEF output requires an NFC tap.")
            val serial = state.serial
            val current = ndefSlot(serial)
            val acc = OtpCodec.parseAccessCode(accessCodeHex)
            // Read the tag over ISO-DEP ourselves. YubiKit's readNdef() only returns the message
            // Android cached during its own NDEF check, which reader mode skips on purpose
            // (skipNdefCheck), so it always failed with "NDEF data missing or invalid".
            var switched = false
            val content = try {
                core.smartCard(device) { conn ->
                    val first = NdefReader.read(conn, knownNdefPrefixes())
                    if (slot == current) return@smartCard first
                    val prefix = first.value.removeSuffix(first.otp)
                    val asText = !first.isUri
                    // A static password's start is only guessed unless the prefix is known; never
                    // write back a prefix that might include part of the password.
                    if (first.staticPassword != null && prefix !in knownNdefPrefixes() + NdefReader.DEFAULT_URI)
                        throw UserFacingException("The NFC output (slot ${current.num}) is a static password with an unrecognised " +
                            "prefix, so it can't be switched to slot ${slot.num} and back safely. Use NFC (NDEF) output to send slot ${slot.num}, then read again.")
                    writeNdef(conn, slot, prefix, asText, acc)
                    switched = true
                    val other = NdefReader.read(conn, knownNdefPrefixes())
                    if (restore) {
                        writeNdef(conn, current, prefix, asText, acc)
                        switched = false
                    }
                    other
                }
            } catch (e: Exception) {
                if (switched) rememberNdefSlot(serial, slot)
                if (e is UserFacingException) throw e
                val where = if (switched) " The NFC output now sends slot ${slot.num}; use NFC (NDEF) output to change it back." else ""
                throw UserFacingException(when (e) {
                    is ApduException ->
                        "The key refused to change the NFC output (SW %04X). If the slot has an access code, enter it.".format(e.sw.toInt() and 0xffff) + where
                    else -> "Could not read the NDEF record: ${e.message}. Hold the key against the phone until it has been read.$where"
                })
            }
            if (slot != current && !restore) rememberNdefSlot(serial, slot)
            val ident = OtpCodec.classifyOutput(content.otp, serial)
                ?: throw UserFacingException("The NDEF message did not contain an OTP.")
            state.identified(serial, slot, ident.copy(
                details = ident.details + ("NDEF payload" to content.otp.take(80))
            ))
            if (slot != current) core.toast(
                if (restore) "Read slot ${slot.num} over NFC; NFC output is back on slot ${current.num}"
                else "Read slot ${slot.num} over NFC; NFC output now sends slot ${slot.num}")
        }
    }

    /** Send the NDEF configuration command over the smart card OTP application. */
    private fun writeNdef(conn: SmartCardConnection, slot: Slot, prefix: String, asText: Boolean, acc: ByteArray?) {
        val data = OtpCodec.buildNdefConfig(prefix.trim(), asText) + (acc ?: ByteArray(6))
        val cmd = if (slot == Slot.ONE) 0x08 else 0x09
        val p = SmartCardProtocol(conn)
        p.select(AppId.OTP)
        // Throws ApduException on any non-9000 status word; the body may be empty.
        p.sendAndReceive(Apdu(0, 0x01, cmd, 0, data))
    }

    /** Read one OTP from the NDEF tag (for the OTP decoder). */
    fun readNdefOtp(onResult: (String) -> Unit) {
        core.run("read an OTP over NFC", refresh = false) { device ->
            val content = try {
                core.smartCard(device) { NdefReader.read(it, knownNdefPrefixes()) }
            } catch (e: Exception) {
                if (e is UserFacingException) throw e
                throw UserFacingException("Could not read the NDEF record: ${e.message}. Hold the key against the phone over NFC.")
            }
            if (content.staticPassword != null) throw UserFacingException(
                "The NFC output is a static password, not a Yubico OTP. Point NFC (NDEF) output at the Yubico OTP slot.")
            onResult(content.otp)
        }
    }

    /** Read the NDEF record the key emits to phones and show it, without leaving the app. */
    fun showNdefOutput() {
        core.run("read the NFC (NDEF) output", refresh = false) { device ->
            val content = try {
                core.smartCard(device) { NdefReader.read(it, knownNdefPrefixes()) }
            } catch (e: Exception) {
                if (e is UserFacingException) throw e
                throw UserFacingException(
                    "Could not read the NDEF record: ${e.message}. Reading it works over NFC; over USB the key may not expose the tag.")
            }
            core.showDialog(InfoDialog("NFC (NDEF) output", describe(content), content.otp))
        }
    }

    /**
     * Point the NFC (NDEF) tag at [slot], prefixed with [prefix] as a URI or as
     * plain text.
     *
     * YubiKit's setNdefConfiguration() insists on parsing a 6-byte status from
     * the reply. Over the smart card interface (NFC, or USB when the keyboard
     * interface is off) the key acknowledges the NDEF command with an empty
     * body, which made that call throw IndexOutOfBoundsException after the
     * write had already succeeded. The command is therefore sent directly
     * here and the reply is only checked for a success status word.
     */
    fun setNdef(slot: Slot, prefix: String, asText: Boolean, accessCodeHex: String) {
        core.run("configure NFC output") { device ->
            val acc = OtpCodec.parseAccessCode(accessCodeHex)
            val usb = device as? UsbYubiKeyDevice
            if (usb != null && usb.supportsConnection(OtpConnection::class.java)) {
                // Keyboard (HID) interface: YubiKit's own implementation is fine here.
                try {
                    core.otp(device) { it.setNdefConfiguration(slot, if (asText) null else prefix.trim().ifBlank { null }, acc) }
                } catch (e: IndexOutOfBoundsException) {
                    // Status could not be parsed but the key accepted the frame.
                }
                if (asText) throw UserFacingException(
                    "Plain-text NDEF can only be written over NFC or the smart card interface. Tap the key over NFC instead.")
            } else {
                core.smartCard(device) { c -> writeNdef(c, slot, prefix, asText, acc) }
            }
            rememberNdefSlot(state.serial, slot)
            rememberNdefPrefix(if (asText) prefix.trim() else prefix.trim().ifBlank { NdefReader.DEFAULT_URI })
            core.toast("NFC (NDEF) output now uses slot ${slot.num}" +
                (if (asText) " as text" else "") + (if (prefix.isBlank()) "" else " with prefix \"${prefix.trim()}\""))
        }
    }

    companion object {
        /** The body of the "NFC (NDEF) output" dialog. */
        fun describe(content: NdefContent): String = buildString {
            append(if (content.isUri) "Link: " else "Text: ").append(content.value)
            append("\n\nSlot output: ").append(content.otp)
            if (content.staticPassword != null) {
                append("\n\nThe slot holds a static password. Over NFC the key sends raw keyboard scan codes " +
                    "instead of characters; they were translated with the US keyboard layout, so a password " +
                    "programmed for another layout may show different characters.")
                content.scanCodesHex?.let { append("\n\nScan codes: ").append(it) }
            }
            else if (content.otp.length in 32..48 && content.otp.all { it in "cbdefghijklnrtuv" }) append("\n\nThis is a Yubico OTP (ModHex).")
            else if (content.otp.length in 6..8 && content.otp.all { it.isDigit() }) append("\n\nThis is an OATH-HOTP code.")
            append("\n\nEach read makes the key generate a new value.")
        }
    }
}

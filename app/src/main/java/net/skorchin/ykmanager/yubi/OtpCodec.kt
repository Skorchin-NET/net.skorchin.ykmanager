package net.skorchin.ykmanager.yubi

import com.yubico.yubikit.yubiotp.YubiOtpSession
import java.nio.ByteBuffer

/**
 * Pure encoding and decoding for the OTP application: classifying what a slot typed,
 * access codes, and the NDEF configuration record. No Android or YubiKey I/O, so it is
 * covered directly by unit tests (OtpCodecTest).
 */
object OtpCodec {
    /** Size of the NDEF data field in the key's NDEF configuration command. */
    const val NDEF_DATA_SIZE = 54
    private const val MODHEX = "cbdefghijklnrtuv"

    /** Public ID derived from a serial number (0xff 0x00 + 4-byte serial), as YubiKey Manager does it. */
    fun serialPublicId(serial: Int): ByteArray =
        ByteBuffer.allocate(6).put(0xff.toByte()).put(0).putInt(serial).array()

    /** Identify a slot's credential type from text it typed (keyboard output or NDEF payload). */
    fun classifyOutput(raw: String, serial: Int?): Identification? {
        val s = raw.trim().trim('\r', '\n', '\t')
        if (s.isEmpty()) return null
        val details = mutableListOf<Pair<String, String>>()

        val isModhex = s.all { it in MODHEX }
        if (isModhex && s.length in 32..64 && s.length % 2 == 0) {
            val pub = s.substring(0, s.length - 32)
            details += "Public ID" to (pub.ifEmpty { "(none)" })
            if (serial != null && pub == Encoding.toModhex(serialPublicId(serial)))
                details += "Public ID source" to "derived from the serial number"
            else if (pub.startsWith("cc")) details += "Origin" to "factory credential (YubiCloud)"
            else if (pub.startsWith("vv")) details += "Origin" to "user-programmed credential"
            details += "OTP length" to "${s.length} characters"
            return Identification(SlotType.YUBICO_OTP, Evidence.OUTPUT, details)
        }

        val hotp = Regex("^([cbdefghijklnrtuv0-9]{0,12}?)(\\d{6}|\\d{8})$").find(s)
        if (hotp != null && s.length <= 20) {
            details += "Digits" to hotp.groupValues[2].length.toString()
            if (hotp.groupValues[1].isNotEmpty()) details += "Token ID" to hotp.groupValues[1]
            return Identification(SlotType.HOTP, Evidence.OUTPUT, details)
        }

        details += "Output length" to "${s.length} characters"
        return Identification(SlotType.STATIC, Evidence.OUTPUT, details)
    }

    /** Parse a 6-byte access code from hex; blank means no access code. */
    fun parseAccessCode(hex: String): ByteArray? {
        val t = hex.trim()
        if (t.isEmpty()) return null
        val b = Encoding.fromHex(t)
        if (b.size != 6) throw UserFacingException("Access codes are 6 bytes (12 hex digits)")
        return b
    }

    /**
     * The NDEF configuration (length, record type, payload, zero padding to [NDEF_DATA_SIZE])
     * for a URI record with [prefix] (abbreviated with the standard URI identifier codes) or,
     * when [asText], an NFC Forum text record in English.
     */
    fun buildNdefConfig(prefix: String, asText: Boolean): ByteArray {
        val payload: ByteArray
        val type: Byte
        if (asText) {
            // NFC Forum text record: status byte (UTF-8, language length) + language code + text
            val lang = "en".toByteArray()
            payload = byteArrayOf(lang.size.toByte()) + lang + prefix.toByteArray(Charsets.UTF_8)
            type = 'T'.code.toByte()
        } else {
            var uri = prefix.ifBlank { YubiOtpSession.DEFAULT_NDEF_URI }
            var idCode = 0
            for ((i, pfx) in NdefReader.URL_PREFIXES.withIndex()) {
                if (uri.startsWith(pfx)) { idCode = i + 1; uri = uri.substring(pfx.length); break }
            }
            payload = byteArrayOf(idCode.toByte()) + uri.toByteArray(Charsets.UTF_8)
            type = 'U'.code.toByte()
        }
        if (payload.size > NDEF_DATA_SIZE) throw UserFacingException(
            "The NDEF prefix is too long (max ${NDEF_DATA_SIZE - (if (asText) 3 else 1)} characters after the standard URI prefix)")
        return ByteBuffer.allocate(2 + NDEF_DATA_SIZE).put(payload.size.toByte()).put(type).put(payload).array()
    }
}

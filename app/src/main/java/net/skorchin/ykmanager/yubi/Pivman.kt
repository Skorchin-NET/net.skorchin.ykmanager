package net.skorchin.ykmanager.yubi

import java.io.ByteArrayOutputStream

/**
 * The "PIV manager" data objects used by ykman and Yubico Authenticator to store a
 * management key on the YubiKey, protected by the PIN. Using the same layout keeps
 * keys set up here usable in the desktop tools and vice versa.
 *
 * - PIVMAN_DATA (0x5FFF00, readable without PIN): 80 { 81 flags, 82 salt, 83 timestamp }
 * - PIVMAN_PROTECTED_DATA (0x5FC109, readable after PIN): 88 { 89 management key }
 */
object Pivman {
    const val OBJ_DATA = 0x5FFF00
    const val OBJ_PROTECTED = 0x5FC109

    const val FLAG_PUK_BLOCKED = 0x01
    const val FLAG_MGM_KEY_STORED = 0x02

    data class Data(val flags: Int? = null, val salt: ByteArray? = null, val timestamp: ByteArray? = null) {
        val keyStored: Boolean get() = ((flags ?: 0) and FLAG_MGM_KEY_STORED) != 0
        val hasDerivedKey: Boolean get() = salt != null

        fun withKeyStored(stored: Boolean): Data {
            val f = (flags ?: 0).let { if (stored) it or FLAG_MGM_KEY_STORED else it and FLAG_MGM_KEY_STORED.inv() }
            return copy(flags = f, salt = null)
        }

        fun encode(): ByteArray {
            val inner = ByteArrayOutputStream()
            flags?.let { inner.write(tlv(0x81, byteArrayOf(it.toByte()))) }
            salt?.let { inner.write(tlv(0x82, it)) }
            timestamp?.let { inner.write(tlv(0x83, it)) }
            return tlv(0x80, inner.toByteArray())
        }

        override fun equals(other: Any?) = other is Data && flags == other.flags &&
            salt.contentEqualsNullable(other.salt) && timestamp.contentEqualsNullable(other.timestamp)
        override fun hashCode() = flags ?: 0
    }

    fun parseData(raw: ByteArray?): Data {
        if (raw == null || raw.isEmpty()) return Data()
        val outer = parse(raw)[0x80] ?: return Data()
        val m = parse(outer)
        return Data(
            flags = m[0x81]?.firstOrNull()?.toInt()?.and(0xff),
            salt = m[0x82],
            timestamp = m[0x83],
        )
    }

    /** The stored management key, or null when the protected object holds none. */
    fun parseProtectedKey(raw: ByteArray?): ByteArray? {
        if (raw == null || raw.isEmpty()) return null
        val outer = parse(raw)[0x88] ?: return null
        return parse(outer)[0x89]?.takeIf { it.isNotEmpty() }
    }

    fun encodeProtected(key: ByteArray?): ByteArray = tlv(0x88, key?.let { tlv(0x89, it) } ?: ByteArray(0))

    // --- minimal BER-TLV (single-byte tags, used by these objects) ---

    fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            value.size < 0x80 -> out.write(value.size)
            value.size <= 0xff -> { out.write(0x81); out.write(value.size) }
            else -> { out.write(0x82); out.write(value.size shr 8); out.write(value.size and 0xff) }
        }
        out.write(value)
        return out.toByteArray()
    }

    fun parse(data: ByteArray): Map<Int, ByteArray> {
        val m = LinkedHashMap<Int, ByteArray>()
        var i = 0
        while (i < data.size) {
            val tag = data[i++].toInt() and 0xff
            if (i >= data.size) break
            var len = data[i++].toInt() and 0xff
            if (len == 0x81) { if (i >= data.size) break; len = data[i++].toInt() and 0xff }
            else if (len == 0x82) {
                if (i + 1 >= data.size) break
                len = ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff); i += 2
            } else if (len > 0x82) break
            if (i + len > data.size) break
            m[tag] = data.copyOfRange(i, i + len)
            i += len
        }
        return m
    }

    private fun ByteArray?.contentEqualsNullable(o: ByteArray?) =
        if (this == null) o == null else o != null && contentEquals(o)
}

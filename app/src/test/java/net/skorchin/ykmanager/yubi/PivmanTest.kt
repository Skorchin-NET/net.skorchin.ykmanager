package net.skorchin.ykmanager.yubi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PivmanTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun emptyOrMissingObjectMeansNoStoredKey() {
        assertFalse(Pivman.parseData(null).keyStored)
        assertFalse(Pivman.parseData(ByteArray(0)).keyStored)
        assertNull(Pivman.parseProtectedKey(null))
    }

    @Test fun parsesYkmanStoredKeyFlag() {
        // 80 03 { 81 01 02 } as written by "ykman piv access change-management-key --protect"
        val d = Pivman.parseData(hex("8003810102"))
        assertTrue(d.keyStored)
        assertFalse(d.hasDerivedKey)
        assertEquals(2, d.flags)
    }

    @Test fun parsesLegacyDerivedKeySalt() {
        val d = Pivman.parseData(hex("8012" + "8210" + "00112233445566778899aabbccddeeff"))
        assertTrue(d.hasDerivedKey)
        assertFalse(d.keyStored)
    }

    @Test fun settingStoredFlagKeepsOtherFlagsAndDropsSalt() {
        val d = Pivman.Data(flags = Pivman.FLAG_PUK_BLOCKED, salt = ByteArray(16)).withKeyStored(true)
        assertEquals(3, d.flags)
        assertNull(d.salt)
        assertEquals("8003810103", d.encode().hex())
        assertEquals(1, d.withKeyStored(false).flags)
    }

    @Test fun protectedKeyRoundTrip() {
        val key = ByteArray(24) { it.toByte() }
        val enc = Pivman.encodeProtected(key)
        assertEquals("881a8918", enc.copyOfRange(0, 4).hex())
        assertArrayEquals(key, Pivman.parseProtectedKey(enc))
        assertNull(Pivman.parseProtectedKey(Pivman.encodeProtected(null)))
        assertEquals("8800", Pivman.encodeProtected(null).hex())
    }

    @Test fun longLengthsAndTruncatedInput() {
        val v = ByteArray(200) { 7 }
        val t = Pivman.tlv(0x89, v)
        assertEquals("8981c8", t.copyOfRange(0, 3).hex())
        assertArrayEquals(v, Pivman.parse(t)[0x89])
        assertTrue(Pivman.parse(t.copyOf(50)).isEmpty())
    }
}

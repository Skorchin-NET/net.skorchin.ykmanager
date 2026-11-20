package net.skorchin.ykmanager.yubi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OtpCodecTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // --- classifyOutput ---------------------------------------------------------------

    @Test fun factoryYubicoOtp() {
        val id = OtpCodec.classifyOutput("ccccccbcgujhingjrdejhgfnuetrgigvejhhgbkugded\n", serial = null)!!
        assertEquals(SlotType.YUBICO_OTP, id.type)
        assertEquals(Evidence.OUTPUT, id.evidence)
        assertTrue("Origin" to "factory credential (YubiCloud)" in id.details)
        assertTrue("OTP length" to "44 characters" in id.details)
    }

    @Test fun serialDerivedPublicIdIsRecognised() {
        val serial = 12345678
        val pub = Encoding.toModhex(OtpCodec.serialPublicId(serial))
        val id = OtpCodec.classifyOutput(pub + "c".repeat(32), serial)!!
        assertEquals(SlotType.YUBICO_OTP, id.type)
        assertTrue("Public ID source" to "derived from the serial number" in id.details)
    }

    @Test fun hotpCodes() {
        val six = OtpCodec.classifyOutput("123456", null)!!
        assertEquals(SlotType.HOTP, six.type)
        assertTrue("Digits" to "6" in six.details)
        val withToken = OtpCodec.classifyOutput("vvcc0112345678", null)!!
        assertEquals(SlotType.HOTP, withToken.type)
        assertTrue("Digits" to "8" in withToken.details)
        assertTrue("Token ID" to "vvcc01" in withToken.details)
    }

    @Test fun anythingElseIsAStaticPassword() {
        val id = OtpCodec.classifyOutput("  hunter2!  ", null)!!
        assertEquals(SlotType.STATIC, id.type)
        assertTrue("Output length" to "8 characters" in id.details)
    }

    @Test fun emptyOutputIsNotIdentified() {
        assertNull(OtpCodec.classifyOutput(" \r\n\t", null))
    }

    // --- parseAccessCode --------------------------------------------------------------

    @Test fun accessCodes() {
        assertNull(OtpCodec.parseAccessCode("   "))
        assertArrayEquals(hex("010203040506"), OtpCodec.parseAccessCode(" 010203040506 "))
        try {
            OtpCodec.parseAccessCode("0102")
            fail("expected UserFacingException")
        } catch (e: UserFacingException) {
            assertEquals("Access codes are 6 bytes (12 hex digits)", e.message)
        }
    }

    // --- buildNdefConfig --------------------------------------------------------------

    @Test fun defaultUriUsesTheHttpsIdentifierCode() {
        val cfg = OtpCodec.buildNdefConfig("", asText = false)
        assertEquals(2 + OtpCodec.NDEF_DATA_SIZE, cfg.size)
        val rest = "my.yubico.com/yk/#".toByteArray()
        assertEquals(1 + rest.size, cfg[0].toInt())          // payload length
        assertEquals('U'.code.toByte(), cfg[1])              // URI record
        assertEquals(0x04.toByte(), cfg[2])                  // "https://"
        assertArrayEquals(rest, cfg.copyOfRange(3, 3 + rest.size))
        assertTrue(cfg.copyOfRange(3 + rest.size, cfg.size).all { it == 0.toByte() })
    }

    @Test fun wwwPrefixIsAbbreviated() {
        val cfg = OtpCodec.buildNdefConfig("https://www.example.com/?otp=", asText = false)
        assertEquals(0x02.toByte(), cfg[2])                  // "https://www."
        assertArrayEquals("example.com/?otp=".toByteArray(), cfg.copyOfRange(3, 3 + 17))
    }

    @Test fun unknownSchemeIsKeptVerbatim() {
        val cfg = OtpCodec.buildNdefConfig("otpauth:", asText = false)
        assertEquals(0x00.toByte(), cfg[2])
        assertArrayEquals("otpauth:".toByteArray(), cfg.copyOfRange(3, 11))
    }

    @Test fun textRecordCarriesLanguage() {
        val cfg = OtpCodec.buildNdefConfig("otp:", asText = true)
        assertEquals('T'.code.toByte(), cfg[1])
        assertArrayEquals(byteArrayOf(2, 'e'.code.toByte(), 'n'.code.toByte()) + "otp:".toByteArray(), cfg.copyOfRange(2, 9))
        assertEquals(7, cfg[0].toInt())
    }

    @Test fun overlongPrefixIsRejected() {
        try {
            OtpCodec.buildNdefConfig("x".repeat(52), asText = true)
            fail("expected UserFacingException")
        } catch (e: UserFacingException) {
            assertTrue(e.message!!.contains("max 51 characters"))
        }
        // The longest text prefix that fits.
        assertEquals(2 + OtpCodec.NDEF_DATA_SIZE, OtpCodec.buildNdefConfig("x".repeat(51), asText = true).size)
    }
}

package net.skorchin.ykmanager.passkey

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasskeyAttachmentTest {

    private fun create(attachment: String?): JSONObject = JSONObject().apply {
        put("rp", JSONObject().put("id", "example.com"))
        put("challenge", "AAAA")
        val sel = JSONObject().put("residentKey", "preferred")
        attachment?.let { sel.put("authenticatorAttachment", it) }
        put("authenticatorSelection", sel)
    }

    @Test fun platformOnlyRequestIsDetected() {
        assertTrue(PasskeyJson.isPlatformOnly(create("platform")))
        assertTrue(PasskeyJson.isPlatformOnly(create(" Platform ").toString()))
    }

    @Test fun otherRequestsAreNotPlatformOnly() {
        assertFalse(PasskeyJson.isPlatformOnly(create("cross-platform")))
        assertFalse(PasskeyJson.isPlatformOnly(create(null)))
        assertFalse(PasskeyJson.isPlatformOnly(JSONObject().put("challenge", "AAAA")))
        assertFalse(PasskeyJson.isPlatformOnly(null as JSONObject?))
        assertFalse(PasskeyJson.isPlatformOnly("not json"))
        assertNull(PasskeyJson.requestedAttachment(create("")))
    }

    @Test fun registrationReportsCrossPlatformUnlessPlatformWasDemanded() {
        assertEquals("cross-platform", PasskeyJson.registrationAttachment(create(null)))
        assertEquals("cross-platform", PasskeyJson.registrationAttachment(create("cross-platform")))
        assertEquals("platform", PasskeyJson.registrationAttachment(create("platform")))
    }

    @Test fun normalizedRequestKeepsAttachment() {
        assertTrue(PasskeyJson.isPlatformOnly(PasskeyJson.normalizeCreate(create("platform"))))
    }

    @Test fun finalizeResponseUsesGivenAttachment() {
        val (json, _) = PasskeyJson.finalizeResponse(JSONObject().put("response", JSONObject()), null,
            PasskeyJson.ATTACHMENT_CROSS_PLATFORM)
        assertEquals("cross-platform", json.getString("authenticatorAttachment"))
        val (def, _) = PasskeyJson.finalizeResponse(JSONObject().put("response", JSONObject()), null)
        assertEquals("platform", def.getString("authenticatorAttachment"))
    }
}

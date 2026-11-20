package net.skorchin.ykmanager.yubi

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NfcTapHandlerTest {

    private class FakeEffects : NfcTapHandler.Effects {
        val events = mutableListOf<String>()
        var dialog: InfoDialog? = null
        override fun openAccounts() { events += "accounts" }
        override fun copy(value: String) { events += "copy:$value" }
        override fun confirmCopied(message: String, background: Boolean) { events += "confirm:$background:$message" }
        override fun openLink(url: String) { events += "open:$url" }
        override fun showDialog(dialog: InfoDialog) { this.dialog = dialog; events += "dialog" }
    }

    private val yubicoOtp = "ccccccbcgujhingjrdejhgfnuetrgigvejhhgbkugded"
    private val otpUri = NdefContent("https://my.yubico.com/yk/#$yubicoOtp", isUri = true)

    /** Short NDEF URI record with the "https://" identifier code. */
    private fun uriRecord(rest: String): ByteArray {
        val payload = byteArrayOf(0x04) + rest.toByteArray()
        return byteArrayOf(0xD1.toByte(), 1, payload.size.toByte(), 'U'.code.toByte()) + payload
    }

    private fun handler(action: NfcTapAction, effects: FakeEffects, copyOnShow: Boolean = false) =
        NfcTapHandler({ action }, { copyOnShow }, { emptySet() }, effects)

    private fun tap(h: NfcTapHandler, intentNdef: ByteArray? = null, tag: NdefContent? = otpUri): Pair<Boolean, Int> {
        var reads = 0
        val handled = runBlocking {
            h.handle(intentNdef) { reads++; tag ?: throw AssertionError("tag should not be read") }
        }
        return handled to reads
    }

    @Test fun refreshLeavesTheTapToTheScreenRefresh() {
        val fx = FakeEffects()
        val (handled, reads) = tap(handler(NfcTapAction.REFRESH, fx), tag = null)
        assertFalse(handled); assertEquals(0, reads); assertTrue(fx.events.isEmpty())
    }

    @Test fun nothingConsumesTheTapWithoutReading() {
        val fx = FakeEffects()
        val (handled, reads) = tap(handler(NfcTapAction.NOTHING, fx), tag = null)
        assertTrue(handled); assertEquals(0, reads); assertTrue(fx.events.isEmpty())
    }

    @Test fun accountsNavigatesAndLetsTheRefreshRun() {
        val fx = FakeEffects()
        val (handled, _) = tap(handler(NfcTapAction.ACCOUNTS, fx), tag = null)
        assertFalse(handled); assertEquals(listOf("accounts"), fx.events)
    }

    @Test fun copyFromTagReadConfirmsInApp() {
        val fx = FakeEffects()
        val (handled, reads) = tap(handler(NfcTapAction.COPY_OTP, fx))
        assertTrue(handled); assertEquals(1, reads)
        assertEquals(listOf("copy:$yubicoOtp", "confirm:false:One-time password copied to the clipboard"), fx.events)
    }

    @Test fun copyFromIntentUsesTheIntentMessageAndConfirmsInBackground() {
        val fx = FakeEffects()
        val (_, reads) = tap(handler(NfcTapAction.COPY_OTP, fx), intentNdef = uriRecord("my.yubico.com/yk/#$yubicoOtp"), tag = null)
        assertEquals(0, reads)
        assertEquals(listOf("copy:$yubicoOtp", "confirm:true:One-time password copied to the clipboard"), fx.events)
    }

    @Test fun unparseableIntentMessageFallsBackToReadingTheTag() {
        val fx = FakeEffects()
        val (_, reads) = tap(handler(NfcTapAction.COPY_OTP, fx), intentNdef = byteArrayOf(1))
        assertEquals(1, reads)
        // The tap still arrived through an intent (another app was in front), so the
        // confirmation goes to the background toast even though the tag was re-read.
        assertEquals("confirm:true:One-time password copied to the clipboard", fx.events.last())
    }

    @Test fun staticPasswordIsNamedInTheConfirmation() {
        val fx = FakeEffects()
        tap(handler(NfcTapAction.COPY_OTP, fx), tag = NdefContent("https://my.yubico.com/yk/#pw", true, staticPassword = "pw"))
        assertEquals(listOf("copy:pw", "confirm:false:Static password copied to the clipboard"), fx.events)
    }

    @Test fun undecodedScanCodesAreNeverCopied() {
        val fx = FakeEffects()
        try {
            tap(handler(NfcTapAction.COPY_OTP, fx), tag = NdefContent("x", false, staticPassword = "\u0001\u0002"))
            fail("expected UserFacingException")
        } catch (e: UserFacingException) {
            assertTrue(e.message!!.contains("nothing was copied"))
        }
        assertTrue(fx.events.isEmpty())
    }

    @Test fun openLinkOpensHttpsLinks() {
        val fx = FakeEffects()
        tap(handler(NfcTapAction.OPEN_LINK, fx))
        assertEquals(listOf("open:${otpUri.value}"), fx.events)
    }

    @Test fun openLinkRefusesOtherSchemes() {
        for (bad in listOf("intent://x#Intent;end", "file:///sdcard/a", "javascript:alert(1)", "HTTPX://a")) {
            val fx = FakeEffects()
            try {
                tap(handler(NfcTapAction.OPEN_LINK, fx), tag = NdefContent(bad, isUri = true))
                fail("expected UserFacingException for $bad")
            } catch (e: UserFacingException) {
                assertTrue(e.message!!.startsWith("Only http(s) links are opened"))
            }
            assertTrue(fx.events.isEmpty())
        }
    }

    @Test fun openLinkRefusesTextRecords() {
        val fx = FakeEffects()
        try {
            tap(handler(NfcTapAction.OPEN_LINK, fx), tag = NdefContent("https://example.com", isUri = false))
            fail("expected UserFacingException")
        } catch (e: UserFacingException) {
            assertTrue(e.message!!.startsWith("The NDEF record is text"))
        }
    }

    @Test fun showDisplaysWithoutCopyingByDefault() {
        val fx = FakeEffects()
        tap(handler(NfcTapAction.SHOW_OTP, fx))
        assertEquals(listOf("dialog"), fx.events)
        val d = fx.dialog!!
        assertEquals("One-time password", d.title)
        assertEquals(yubicoOtp, d.copyValue)
        assertTrue(d.body.startsWith(yubicoOtp))
        assertTrue(d.body.contains("Link: ${otpUri.value}"))
        assertFalse(d.body.contains("copied"))
    }

    @Test fun showCopiesWhenConfigured() {
        val fx = FakeEffects()
        tap(handler(NfcTapAction.SHOW_OTP, fx, copyOnShow = true))
        assertEquals(listOf("copy:$yubicoOtp", "dialog"), fx.events)
        assertTrue(fx.dialog!!.body.endsWith("(copied to the clipboard)"))
    }

    @Test fun showExplainsStaticPasswords() {
        val d = NfcTapHandler.otpDialog(NdefContent("pw", false, staticPassword = "pw"), copied = false)
        assertEquals("Static password", d.title)
        assertTrue(d.body.contains("US keyboard layout"))
        assertFalse(d.body.contains("Text:"))
    }

    @Test fun uriSchemeFollowsAndroidUriParsing() {
        assertEquals("https", NfcTapHandler.uriScheme("HTTPS://my.yubico.com/yk/#abc"))
        assertEquals("http", NfcTapHandler.uriScheme("http://a"))
        assertEquals("intent", NfcTapHandler.uriScheme("intent:#Intent;end"))
        assertNull(NfcTapHandler.uriScheme("no-scheme"))
        assertNull(NfcTapHandler.uriScheme(":nothing"))
        assertNull(NfcTapHandler.uriScheme("/path/with:colon"))
        assertNull(NfcTapHandler.uriScheme("a?b:c"))
    }

    @Test fun readableRejectsEmptyAndControlCharacters() {
        assertTrue(NfcTapHandler.isReadable("abc123!"))
        assertFalse(NfcTapHandler.isReadable(""))
        assertFalse(NfcTapHandler.isReadable("ab\tc"))
        assertFalse(NfcTapHandler.isReadable("ab\uFFFD"))
    }
}

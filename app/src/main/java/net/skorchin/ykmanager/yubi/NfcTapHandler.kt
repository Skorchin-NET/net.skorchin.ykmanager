package net.skorchin.ykmanager.yubi

/**
 * Decides what an NFC tap does when nothing in the app was waiting for the key (the
 * user's "NFC tap" setting). Everything that touches Android (clipboard, activities,
 * dialogs, navigation) goes through [Effects], so the decisions are plain JVM code that
 * unit tests can drive without a phone or a YubiKey.
 */
class NfcTapHandler(
    private val tapAction: () -> NfcTapAction,
    private val copyOnShow: () -> Boolean,
    private val knownPrefixes: () -> Set<String>,
    private val effects: Effects,
) {
    /** Side effects of a tap, implemented by the view model. */
    interface Effects {
        fun openAccounts()
        fun copy(value: String)
        /** Confirm a copy; [background] is true when the tap arrived while another app was in front. */
        fun confirmCopied(message: String, background: Boolean)
        /** Open an http(s) link that [handle] has already validated. */
        fun openLink(url: String)
        fun showDialog(dialog: InfoDialog)
    }

    /**
     * Handles one tap. [intentNdef] is the NDEF message when the tap came in through an
     * NDEF intent; otherwise (or if it can't be parsed) [readTag] reads the record from the
     * key. Returns true when the tap was fully handled and no screen refresh should follow.
     * Problems the user should see are thrown as [UserFacingException].
     */
    suspend fun handle(intentNdef: ByteArray?, readTag: suspend (Set<String>) -> NdefContent): Boolean {
        val action = tapAction()
        when (action) {
            NfcTapAction.REFRESH -> return false
            NfcTapAction.NOTHING -> return true
            NfcTapAction.ACCOUNTS -> { effects.openAccounts(); return false }
            NfcTapAction.COPY_OTP, NfcTapAction.SHOW_OTP, NfcTapAction.OPEN_LINK -> Unit
        }
        val prefixes = knownPrefixes()
        val content = intentNdef?.let { runCatching { NdefReader.parse(it, prefixes) }.getOrNull() }
            ?: readTag(prefixes)
        when (action) {
            NfcTapAction.COPY_OTP -> copyOtp(content, background = intentNdef != null)
            NfcTapAction.OPEN_LINK -> openLink(content)
            else -> showOtp(content)
        }
        return true
    }

    private fun copyOtp(content: NdefContent, background: Boolean) {
        // Never put undecoded keyboard scan codes (control characters) on the clipboard.
        if (!isReadable(content.otp)) throw UserFacingException(
            "The NFC output could not be decoded as readable text, so nothing was " +
                "copied. Use OTP slots → Show NFC output to see the raw scan codes.")
        effects.copy(content.otp)
        val what = if (content.staticPassword != null) "Static password" else "One-time password"
        effects.confirmCopied("$what copied to the clipboard", background)
    }

    private fun openLink(content: NdefContent) {
        if (!content.isUri) throw UserFacingException("The NDEF record is text, not a link: ${content.value}")
        // Only web links: a tag could otherwise carry intent:, file: or other schemes.
        if (uriScheme(content.value) !in WEB_SCHEMES)
            throw UserFacingException("Only http(s) links are opened. The record is: ${content.value}")
        effects.openLink(content.value)
    }

    private fun showOtp(content: NdefContent) {
        val copy = copyOnShow()
        if (copy) effects.copy(content.otp)
        effects.showDialog(otpDialog(content, copied = copy))
    }

    companion object {
        private val WEB_SCHEMES = setOf("http", "https")

        /** True when [otp] is non-empty and has no control characters or replacement characters. */
        fun isReadable(otp: String): Boolean = otp.isNotEmpty() && otp.none { it < ' ' || it == '\uFFFD' }

        /**
         * The lower-cased scheme of [uri], parsed the way android.net.Uri does: the text before
         * the first ':' if no '/', '?' or '#' comes before it; null when there is none.
         */
        fun uriScheme(uri: String): String? {
            val colon = uri.indexOf(':')
            if (colon <= 0) return null
            val sep = uri.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (sep in 0 until colon) return null
            return uri.substring(0, colon).lowercase()
        }

        /** The dialog shown by the "Show the NDEF one-time password" tap action. */
        fun otpDialog(content: NdefContent, copied: Boolean): InfoDialog {
            val isStatic = content.staticPassword != null
            val body = buildString {
                append(content.otp)
                if (content.otp != content.value)
                    append("\n\n").append(if (content.isUri) "Link" else "Text").append(": ").append(content.value)
                if (isStatic) append("\n\nThe slot holds a static password. Over NFC the key sends raw keyboard scan " +
                    "codes, which were translated using the US keyboard layout; characters typed on another layout may differ.")
                if (copied) append("\n\n(copied to the clipboard)")
            }
            return InfoDialog(if (isStatic) "Static password" else "One-time password", body, content.otp)
        }
    }
}

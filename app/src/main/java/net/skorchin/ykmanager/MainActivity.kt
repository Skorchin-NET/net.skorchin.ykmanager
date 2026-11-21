package net.skorchin.ykmanager

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.core.content.IntentCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import net.skorchin.ykmanager.ui.AppRoot
import net.skorchin.ykmanager.ui.theme.YkManagerTheme
import net.skorchin.ykmanager.yubi.AppViewModel

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.core.startUsbDiscovery()
        // Keep the passkey provider's list of trusted browsers current (at most once a day).
        Thread { net.skorchin.ykmanager.passkey.CallerVerifier.refreshAllowlistIfStale(applicationContext) }.start()
        setContent {
            YkManagerTheme {
                AppRoot(vm)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // The tap arrived while another app was in front and the setting is "copy":
                // confirm with a system toast and go back to where the user was.
                vm.copiedInBackground.collect { msg ->
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                    moveTaskToBack(true)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Whenever the app starts waiting for a tap, make sure it really holds reader mode,
                // so the tap is not handed to Android (which opens the key's my.yubico.com link).
                vm.core.tapPrompt.collect { prompt ->
                    if (prompt != null && !prompt.startsWith("Unplug")) vm.core.rearmNfcForPrompt(this@MainActivity)
                }
            }
        }
        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    /**
     * Fallback for taps the system dispatched as an NDEF/tag intent (the
     * manifest registers the YubiKey's NDEF URL). Normally reader mode
     * delivers taps directly; this catches the cases where Android handed
     * the tag to the system instead, so the user lands in the app with the
     * tap treated like any other scan.
     */
    @Suppress("DEPRECATION") // ACTION_TAG_DISCOVERED is still dispatched by the system as a fallback
    private fun handleNfcIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED && action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED) return
        val tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return
        // The text-record filter also matches ordinary NFC stickers; a YubiKey is always ISO-DEP.
        if (IsoDep::class.java.name !in tag.techList) { intent.action = null; return }
        val ndef = IntentCompat.getParcelableArrayExtra(intent, NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
            ?.firstOrNull()?.let { (it as? NdefMessage)?.toByteArray() }
        intent.action = null // handle once
        vm.core.onNfcIntentTag(tag, ndef)
    }

    override fun onResume() {
        super.onResume()
        net.skorchin.ykmanager.yubi.ConnectionLog.add("App in front")
        vm.core.appInFront = true
        // A passkey sheet cannot be showing while this screen is in front; clear a flag it may have left behind.
        if (net.skorchin.ykmanager.passkey.PasskeyPromptState.active) {
            net.skorchin.ykmanager.yubi.ConnectionLog.add("Passkey prompt flag was left on; cleared")
            net.skorchin.ykmanager.passkey.PasskeyPromptState.active = false
        }
        vm.core.startNfcDiscovery(this)
        vm.core.checkUsb(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Make sure reader mode is held whenever the app is in front, but never restart it
        // while it is already active: restarting re-polls the field and a key still on the
        // phone would be read by the system (and its NDEF link opened) in the gap.
        if (hasFocus) vm.core.ensureNfcDiscovery(this)
    }

    override fun onPause() {
        vm.core.appInFront = false
        vm.core.stopNfcDiscovery(this)
        super.onPause()
    }
}

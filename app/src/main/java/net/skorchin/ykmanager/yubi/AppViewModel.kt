package net.skorchin.ykmanager.yubi

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.management.Capability
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.core.net.toUri

enum class Screen(val title: String, val capability: Capability? = null) {
    HOME("Home"),
    ACCOUNTS("Accounts", Capability.OATH),
    PASSKEYS("Passkeys", Capability.FIDO2),
    FINGERPRINTS("Fingerprints", Capability.FIDO2),
    CERTIFICATES("Certificates", Capability.PIV),
    SLOTS("OTP slots", Capability.OTP),
    OPENPGP("OpenPGP", Capability.OPENPGP),
    APPLICATIONS("Applications"),
    RECIPES("Recipes"),
    RESET("Factory reset"),
    INVENTORY("Full inventory"),
    SETTINGS("Settings"),
    ABOUT("About & legal");

    /** Whether this screen makes sense for the connected key (unknown key: show everything). */
    fun availableFor(info: KeyInfo?): Boolean {
        if (info == null) return true
        if (this == FINGERPRINTS) return info.isBio
        val cap = capability ?: return true
        return info.supports(cap)
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val core = YubiKeyCore(app)
    val otp = OtpFeature(core, app)
    val oath = OathFeature(core, app)
    val piv = PivFeature(core)
    val fido = FidoFeature(core)
    val pgp = OpenPgpFeature(core)
    val mgmt = ManagementFeature(core)
    val recipes = RecipeFeature(core, otp, mgmt, app)
    val settings = AppSettings(app)

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    init {
        core.screenRefresher = { device ->
            when (_screen.value) {
                Screen.ACCOUNTS -> oath.refresh(device)
                Screen.PASSKEYS -> fido.refresh(device)
                Screen.FINGERPRINTS -> fido.refresh(device)
                Screen.CERTIFICATES -> piv.refresh(device)
                Screen.OPENPGP -> pgp.refresh(device)
                Screen.SLOTS -> otp.rebuild()
                Screen.INVENTORY -> collectInventory(device)
                else -> Unit
            }
        }
        core.onKeyChanged = {
            oath.clear(); piv.clear(); fido.clear(); pgp.clear(); _inventoryAt.value = null
        }
        core.idleTapHandler = { device, intentNdef -> handleIdleTap(device, intentNdef) }
        viewModelScope.launch {
            core.keyInfo.collect { info ->
                otp.rebuild()
                if (info != null && !_screen.value.availableFor(info)) _screen.value = Screen.HOME
            }
        }
        // Keep TOTP codes fresh while a key is plugged in over USB.
        viewModelScope.launch {
            while (true) {
                delay(1000)
                if (_screen.value == Screen.ACCOUNTS && core.isUsb && !core.busy.value) {
                    val st = oath.state.value
                    val now = System.currentTimeMillis()
                    val expired = st.loaded && !st.locked && st.entries.any {
                        it.isTotp && it.code != null && it.code.validUntil <= now
                    }
                    if (expired && now - st.readAt > 3000) core.refreshNow()
                }
            }
        }
    }

    private val _inventoryErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Applications that could not be read during the last inventory, with the reason. */
    val inventoryErrors: StateFlow<Map<String, String>> = _inventoryErrors.asStateFlow()
    private val _inventoryAt = MutableStateFlow<Long?>(null)
    val inventoryAt: StateFlow<Long?> = _inventoryAt.asStateFlow()

    /** Read every application the key supports, for the Full inventory screen. */
    private suspend fun collectInventory(device: com.yubico.yubikit.core.YubiKeyDevice) {
        val info = core.keyInfo.value
        val errors = linkedMapOf<String, String>()
        suspend fun part(name: String, cap: Capability?, block: suspend () -> Unit) {
            if (cap != null && info != null && !info.supports(cap)) return
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                errors[name] = (e as? UserFacingException)?.message ?: YubiKeyCore.describe(e)
            }
        }
        part("OATH", Capability.OATH) { oath.refresh(device) }
        part("FIDO2", Capability.FIDO2) { fido.refresh(device) }
        part("PIV", Capability.PIV) { piv.refresh(device, includeRetired = true) }
        part("OpenPGP", Capability.OPENPGP) { pgp.refresh(device) }
        part("OTP", Capability.OTP) { otp.rebuild() }
        _inventoryErrors.value = errors
        _inventoryAt.value = System.currentTimeMillis()
    }

    private val _copiedInBackground = Channel<String>(Channel.BUFFERED)
    /**
     * Emits a confirmation when a tap that reached the app through an NFC intent (the app
     * was not in front) was copied to the clipboard; the activity then shows it as a system
     * toast and returns the user to the app they were using.
     */
    val copiedInBackground: Flow<String> = _copiedInBackground.receiveAsFlow()

    /** The configurable behaviour for a tap that nothing was waiting for (see [NfcTapHandler]). */
    private val tapHandler = NfcTapHandler(
        tapAction = { settings.nfcTapAction.value },
        copyOnShow = { settings.copyOnShow.value },
        knownPrefixes = { otp.knownNdefPrefixes() },
        effects = object : NfcTapHandler.Effects {
            override fun openAccounts() { _screen.value = Screen.ACCOUNTS }
            override fun copy(value: String) = copy(getApplication(), value)
            override fun confirmCopied(message: String, background: Boolean) {
                if (background) _copiedInBackground.trySend(message) else core.toast(message)
            }
            override fun openLink(url: String) {
                getApplication<Application>().startActivity(
                    Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            override fun showDialog(dialog: InfoDialog) = core.showDialog(dialog)
        },
    )

    private suspend fun handleIdleTap(device: NfcYubiKeyDevice, intentNdef: ByteArray?): Boolean =
        tapHandler.handle(intentNdef) { prefixes -> core.smartCard(device) { NdefReader.read(it, prefixes) } }

    private fun copy(ctx: Context, value: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(net.skorchin.ykmanager.ui.sensitiveClip("One-time password", value))
    }

    fun navigate(s: Screen) {
        if (_screen.value == s) return
        _screen.value = s
        if (core.isUsb) core.refreshNow()
    }

    override fun onCleared() {
        core.close()
    }
}

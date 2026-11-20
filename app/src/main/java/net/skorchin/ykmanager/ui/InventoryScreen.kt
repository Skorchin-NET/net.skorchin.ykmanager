package net.skorchin.ykmanager.ui

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yubico.yubikit.core.Transport
import com.yubico.yubikit.management.Capability
import com.yubico.yubikit.oath.OathType
import com.yubico.yubikit.piv.TouchPolicy
import com.yubico.yubikit.yubiotp.Slot
import net.skorchin.ykmanager.yubi.AppViewModel
import net.skorchin.ykmanager.yubi.FidoState
import net.skorchin.ykmanager.yubi.InvSection
import net.skorchin.ykmanager.yubi.Inventory
import net.skorchin.ykmanager.yubi.InventoryReport
import net.skorchin.ykmanager.yubi.KeyInfo
import net.skorchin.ykmanager.yubi.OathState
import net.skorchin.ykmanager.yubi.PgpState
import net.skorchin.ykmanager.yubi.PivFeature
import net.skorchin.ykmanager.yubi.PivState
import net.skorchin.ykmanager.yubi.SlotView
import java.text.DateFormat
import java.util.Date

@Composable
fun InventoryScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val printHolder = remember { arrayOfNulls<WebView>(1) }
    val keyInfo by vm.core.keyInfo.collectAsStateWithLifecycle()
    val oath by vm.oath.state.collectAsStateWithLifecycle()
    val favorites by vm.oath.favorites.collectAsStateWithLifecycle()
    val fido by vm.fido.state.collectAsStateWithLifecycle()
    val piv by vm.piv.state.collectAsStateWithLifecycle()
    val pgp by vm.pgp.state.collectAsStateWithLifecycle()
    val otpSlots by vm.otp.slots.collectAsStateWithLifecycle()
    val errors by vm.inventoryErrors.collectAsStateWithLifecycle()
    val collectedAt by vm.inventoryAt.collectAsStateWithLifecycle()

    ScreenColumn {
        val k = keyInfo
        if (k == null || collectedAt == null) {
            NeedsKey("a full inventory of every application") { vm.core.refreshNow() }
            return@ScreenColumn
        }
        val report = buildInventory(
            k, collectedAt!!, oath, { vm.oath.isFavorite(oath.deviceId, it, favorites) }, fido, piv, pgp, otpSlots,
            vm.otp.ndefSlot(k.serial).takeIf { vm.otp.ndefSlotKnown(k.serial) }, errors,
        )
        SectionCard {
            SectionTitle(report.title)
            Hint("Collected ${report.generatedAt}. Lists configuration only, never secrets, PINs or codes. " +
                "Account names and website names are included.")
            Spacer(Modifier.height(8.dp))
            ButtonRow {
                SmallButton("Print or save PDF") { printInventory(context, report, printHolder) }
                SmallButton("Share as text") { shareInventory(context, report) }
                SmallButton("Collect again") { vm.core.refreshNow() }
            }
        }
        if (fido.loaded && fido.pinSet && fido.credMgmtSupported && fido.passkeys == null) {
            Hint("To include passkeys and fingerprints, unlock them on the Passkeys screen first, then collect again.")
        }
        if (oath.locked) Hint("The Accounts application is password-protected. Unlock it on the Accounts screen to include accounts.")
        report.sections.forEach { s -> SectionView(s, top = true) }
    }
}

@Composable
private fun SectionView(s: InvSection, top: Boolean) {
    if (top) SectionCard { SectionBody(s, true) } else SectionBody(s, false)
}

@Composable
private fun SectionBody(s: InvSection, top: Boolean) {
    if (top) SectionTitle(s.title)
    else {
        Spacer(Modifier.height(6.dp))
        Text(s.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(4.dp))
    s.rows.forEach { (k, v) -> InfoRow(k, v) }
    s.notes.forEach { Hint(it) }
    s.groups.forEach { SectionView(it, top = false) }
}

private fun yesNo(b: Boolean) = if (b) "yes" else "no"

internal fun buildInventory(
    k: KeyInfo,
    collectedAt: Long,
    oath: OathState,
    isFavorite: (net.skorchin.ykmanager.yubi.OathEntry) -> Boolean,
    fido: FidoState,
    piv: PivState,
    pgp: PgpState,
    otpSlots: List<SlotView>,
    ndefSlot: Slot?,
    errors: Map<String, String>,
): InventoryReport {
    val d = k.deviceInfo
    val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val serial = (d?.serialNumber ?: k.serial)?.toString()
    val sections = mutableListOf<InvSection>()

    // Device ----------------------------------------------------------------
    sections += InvSection(
        "Device",
        rows = buildList {
            add("Model" to k.name)
            add("Serial number" to (serial ?: "hidden"))
            add("Firmware" to (d?.version?.toString() ?: k.otpVersion?.toString() ?: "unknown"))
            d?.let { add("Form factor" to formFactorName(it.formFactor)) }
            d?.partNumber?.takeIf { it.isNotBlank() }?.let { add("Part number" to it) }
            if (d != null) {
                if (d.isFips) add("FIPS series" to "yes")
                if (d.isSky) add("Security Key" to "yes")
                if (k.isBio) add("Fingerprint sensor" to ("yes" + (d.fpsVersion?.let { " (sensor fw $it)" } ?: "")))
                if (d.pinComplexity) add("PIN complexity" to "enforced")
                if (k.hasDeviceConfig) add("Config lock code" to if (d.isLocked) "set" else "not set")
                d.config.autoEjectTimeout?.let { add("Auto-eject" to if (it.toInt() == 0) "off" else "${it}s") }
                d.config.challengeResponseTimeout?.let { add("Chal-resp timeout" to "${it}s") }
                for (t in listOf(Transport.USB, Transport.NFC)) {
                    if (!d.hasTransport(t)) continue
                    val supported = d.getSupportedCapabilities(t)
                    val enabled = d.config.getEnabledCapabilities(t) ?: 0
                    val label = if (t == Transport.USB) "USB" else "NFC"
                    add("$label enabled" to Capability.entries.filter { enabled and supported and it.bit != 0 }
                        .joinToString { capLabel(it) }.ifEmpty { "none" })
                    val off = Capability.entries.filter { supported and it.bit != 0 && enabled and it.bit == 0 }
                    if (off.isNotEmpty()) add("$label disabled" to off.joinToString { capLabel(it) })
                }
            }
            add("Read over" to if (k.transport == Transport.USB) "USB" else "NFC")
        },
    )

    fun unreadable(name: String) = errors[name]?.let { listOf("Could not be read: $it") } ?: listOf("Not read.")

    // OTP -------------------------------------------------------------------
    if (k.supports(Capability.OTP)) {
        sections += InvSection(
            "OTP slots",
            rows = buildList {
                if (ndefSlot != null) add("NFC (NDEF) slot" to if (ndefSlot == Slot.ONE) "Slot 1" else "Slot 2")
            },
            notes = if (otpSlots.isEmpty()) unreadable("OTP") else emptyList(),
            groups = otpSlots.map { v ->
                InvSection(
                    if (v.slot == Slot.ONE) "Slot 1 (short touch)" else "Slot 2 (long touch)",
                    rows = buildList {
                        add("Configured" to when (v.configured) { true -> "yes"; false -> "no"; null -> "unknown" })
                        v.type?.let { add("Type" to it.label) }
                        v.evidence?.let { add("Known from" to it.label) }
                        v.touchTriggered?.let { add("Triggered by touch" to yesNo(it)) }
                        v.history?.let { add("Programmed by this app" to net.skorchin.ykmanager.yubi.OtpFeature.formatTime(it.programmedAt)) }
                        addAll(v.details)
                    },
                )
            },
        )
    }

    // OATH ------------------------------------------------------------------
    if (k.supports(Capability.OATH)) {
        sections += if (!oath.loaded) InvSection("Accounts (OATH)", notes = unreadable("OATH")) else InvSection(
            "Accounts (OATH)",
            rows = buildList {
                oath.version?.let { add("Version" to it.toString()) }
                add("Password" to if (oath.hasPassword) "set" else "not set")
                add("Accounts" to if (oath.locked) "locked" else oath.entries.size.toString())
            },
            notes = if (oath.locked) listOf("Unlock the Accounts screen to list accounts.") else emptyList(),
            groups = if (oath.locked) emptyList() else oath.entries
                .sortedBy { (it.issuer ?: it.name).lowercase() }
                .map { e ->
                    val c = e.credential
                    InvSection(
                        listOfNotNull(e.issuer, e.name).joinToString(" — "),
                        rows = buildList {
                            add("Type" to if (c.oathType == OathType.TOTP) "TOTP (${c.period}s)" else "HOTP")
                            add("Touch required" to yesNo(c.isTouchRequired))
                            if (isFavorite(e)) add("Pinned" to "yes")
                        },
                    )
                },
        )
    }

    // FIDO ------------------------------------------------------------------
    if (k.supports(Capability.FIDO2)) {
        sections += if (!fido.loaded) InvSection("FIDO2 / passkeys", notes = unreadable("FIDO2")) else InvSection(
            "FIDO2 / passkeys",
            rows = buildList {
                add("Versions" to fido.versions.joinToString())
                fido.aaguid?.let { add("AAGUID" to it) }
                add("PIN" to if (fido.pinSet) "set" else "not set")
                fido.pinRetries?.let { add("PIN retries left" to it.toString()) }
                add("Minimum PIN length" to fido.minPinLength.toString())
                add("Force PIN change" to if (fido.forcePinChange) "pending" else "no")
                fido.alwaysUv?.let { add("Always require UV" to if (it) "on" else "off") }
                fido.existingCount?.let { add("Passkeys stored" to it.toString()) }
                fido.remainingCredentials?.let { add("Free passkey slots" to it.toString()) }
                if (fido.bioSupported) add("Fingerprints" to (fido.fingerprints?.size?.toString()
                    ?: if (fido.bioEnrolled) "enrolled (unlock to list)" else "none"))
            },
            notes = if (fido.pinSet && fido.credMgmtSupported && fido.passkeys == null)
                listOf("Passkeys not listed: unlock them on the Passkeys screen first.") else emptyList(),
            groups = buildList {
                fido.passkeys?.takeIf { it.isNotEmpty() }?.let { list ->
                    add(InvSection("Passkeys", rows = list.sortedWith(compareBy({ it.rpId }, { it.userName }))
                        .map { p -> p.rpId to listOfNotNull(p.displayName?.takeIf { it != p.userName }, p.userName).joinToString(" · ") }))
                }
                fido.fingerprints?.takeIf { it.isNotEmpty() }?.let { fps ->
                    add(InvSection("Enrolled fingerprints", rows = fps.mapIndexed { i, f -> "#${i + 1}" to f.name }))
                }
            },
        )
    }

    // PIV -------------------------------------------------------------------
    if (k.supports(Capability.PIV)) {
        sections += if (!piv.loaded) InvSection("Certificates (PIV)", notes = unreadable("PIV")) else InvSection(
            "Certificates (PIV)",
            rows = buildList {
                piv.version?.let { add("Version" to it.toString()) }
                val pin = piv.pin
                if (pin != null) add("PIN" to "${pin.attemptsRemaining}/${pin.totalAttempts} tries" + if (pin.isDefaultValue) " · DEFAULT" else "")
                else piv.pinAttempts?.let { add("PIN tries left" to it.toString()) }
                piv.puk?.let { add("PUK" to "${it.attemptsRemaining}/${it.totalAttempts} tries" + if (it.isDefaultValue) " · DEFAULT" else "") }
                val mk = piv.managementKey
                if (mk != null) add("Management key" to mk.keyType.name + (if (mk.isDefaultValue) " · DEFAULT" else "") +
                    (if (mk.touchPolicy != TouchPolicy.NEVER && mk.touchPolicy != TouchPolicy.DEFAULT) " · touch" else ""))
                add("Management key protected by PIN" to yesNo(piv.pinUnlocksMgmtKey))
                add("Slots in use" to piv.slots.count { !it.isEmpty }.toString())
            },
            groups = piv.slots.filter { !it.isEmpty }.map { v ->
                val c = v.certificate
                InvSection(
                    PivFeature.slotName(v.slot),
                    rows = buildList {
                        v.metadata?.let { m ->
                            add("Key type" to m.keyType.name)
                            add("Origin" to if (m.isGenerated) "generated on YubiKey" else "imported")
                            add("PIN policy" to m.pinPolicy.name.lowercase())
                            add("Touch policy" to m.touchPolicy.name.lowercase())
                        }
                        if (c == null) add("Certificate" to "none")
                        else {
                            add("Subject" to c.subjectX500Principal.name)
                            add("Issuer" to if (c.issuerX500Principal == c.subjectX500Principal) "self-signed" else c.issuerX500Principal.name)
                            add("Serial" to c.serialNumber.toString(16).uppercase())
                            add("Valid from" to df.format(c.notBefore))
                            add("Valid to" to df.format(c.notAfter) + if (c.notAfter.time < collectedAt) " (EXPIRED)" else "")
                            add("SHA-256" to PivFeature.fingerprint(c))
                        }
                    },
                )
            },
        )
    }

    // OpenPGP ---------------------------------------------------------------
    if (k.supports(Capability.OPENPGP)) {
        sections += if (!pgp.loaded) InvSection("OpenPGP", notes = unreadable("OpenPGP")) else InvSection(
            "OpenPGP",
            rows = buildList {
                pgp.version?.let { add("Version" to it.toString()) }
                pgp.userAttempts?.let { add("User PIN tries" to it.toString()) }
                pgp.resetAttempts?.let { add("Reset code tries" to it.toString()) }
                pgp.adminAttempts?.let { add("Admin PIN tries" to it.toString()) }
                pgp.signatureCounter?.let { add("Signatures made" to it.toString()) }
                pgp.signaturePinPolicy?.let { add("Signature PIN" to it) }
            },
            groups = pgp.keys.map { key ->
                InvSection(
                    keyRefName(key.ref),
                    rows = buildList {
                        key.algorithm?.let { add("Algorithm" to it) }
                        if (key.fingerprint == null) add("Status" to "empty")
                        else {
                            add("Fingerprint" to key.fingerprint.chunked(4).joinToString(" "))
                            key.created?.let { add("Created" to net.skorchin.ykmanager.yubi.OtpFeature.formatTime(it)) }
                            key.status?.let { add("Origin" to it.replace('_', ' ')) }
                        }
                        add("Touch" to uifName(key.touch))
                    },
                )
            },
        )
    }

    val title = "YubiKey inventory — ${k.name}" + (serial?.let { " #$it" } ?: "")
    return InventoryReport(title, DateFormat.getDateTimeInstance().format(Date(collectedAt)), sections)
}

/** [keep] holds the WebView until the print framework has laid out the document. */
private fun printInventory(context: Context, report: InventoryReport, keep: Array<WebView?>) {
    val web = WebView(context)
    // Static, app-generated HTML only: no JavaScript, network or file access.
    web.settings.javaScriptEnabled = false
    web.settings.allowFileAccess = false
    web.settings.allowContentAccess = false
    web.settings.blockNetworkLoads = true
    web.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val name = "YubiKey inventory"
            val margin = Inventory.PRINT_MARGIN_MILS
            val margins = PrintAttributes.Margins(margin, margin, margin, margin)
            pm.print(name, MarginPrintAdapter(view.createPrintDocumentAdapter(name), margins), null)
            keep[0] = null
        }
    }
    keep[0] = web
    web.loadDataWithBaseURL(null, Inventory.html(report), "text/html", "UTF-8", null)
}

private fun shareInventory(context: Context, report: InventoryReport) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, report.title)
        putExtra(Intent.EXTRA_TEXT, Inventory.text(report))
    }
    context.startActivity(Intent.createChooser(send, "Share inventory"))
}

# YK Manager for Android

A starter Android app that manages a YubiKey the way the desktop **YubiKey Manager (ykman)** does, built on Yubico's official Android SDK ([YubiKit](https://developers.yubico.com/yubikit-android/index.html)).

## Features

Navigation drawer with the same sections as desktop YubiKey Manager:

- **Home**: form factor, firmware, serial, part number, FIPS / Security Key flags, config lock state, timeouts, and enabled vs. supported applications per transport (USB / NFC).
- **Accounts (OATH)**: TOTP/HOTP codes with countdown and auto-refresh over USB, tap to calculate touch/HOTP accounts, copy codes, add by QR scan, `otpauth://` URI or manual entry, rename, delete, and set/change/remove the OATH password (optionally remembered on the phone). **Pin to top** stars an account and lists it first (stored on this phone).
- **Passkeys (FIDO2)**: versions, AAGUID, PIN status/retries, min PIN length, free credential slots; set/change PIN; unlock with the PIN to list and delete resident passkeys. Tap a passkey for its full record: RP ID/name/hash, user handle, credential ID (hex + base64url), transports, credProtect level, large-blob key, and the public key (algorithm, curve, point, fingerprint), each copyable.
- **Certificates (PIV)**: all four main slots (plus retired slots on demand) with subject, issuer, serial, validity, signature algorithm, SHA-256 fingerprint, key type, origin and PIN/touch policy. Generate keys (ECC P-256/P-384, RSA 2048/3072/4096) with a self-signed certificate or CSR, import PKCS#12 / PEM / DER, export certificates, delete certificates (and keys on firmware 5.7+), change PIN / PUK / management key, unblock PIN, set retry counts. **Protect with PIN** stores a random management key on the YubiKey, unlocked by the PIN, in the same format as ykman and Yubico Authenticator (legacy PIN-derived keys also work).
- **OTP slots**: reads the slot configuration the YubiKey reports (programmed/empty, touch-triggered vs. challenge-response) and identifies the exact type: captures what the slot types over USB, reads the NFC NDEF tag, or runs a challenge-response test. It also keeps a local history of what this app programmed (never secrets). You can program Yubico OTP / HOTP / static password / HMAC-SHA1 challenge-response with access codes, and delete, swap, calculate HMAC, and configure NDEF output. Output settings from the Personalization Tool's Settings tab (tabs, Enter, delays, pacing, numeric keypad, fast trigger, reference string, serial number visibility, LED, allow update) live under **Settings → OTP output** and can be pushed to an existing slot with **Update settings**. The **OTP decoder** decrypts a Yubico OTP with its AES key to show the private ID, counters and timer (the key never outputs the private ID in the clear).
- **OpenPGP**: version, PIN retry counters, signature counter, signature PIN policy, and per-key fingerprint / creation date / origin / touch policy. Change PIN / admin PIN, set reset code, unblock PIN, set retry counts, change touch policies.
- **Applications**: enable/disable each application over USB and NFC, set/change/remove the configuration lock code, auto-eject and challenge-response timeouts.
- **Full inventory**: one read collects every application the key supports (device, OTP slots, accounts, FIDO2/passkeys, PIV certificates, OpenPGP) into a single report you can print, save as a PDF through Android's print dialog, or share as text. It lists configuration only, never secrets, PINs or codes.
- **Factory reset** (under Settings): OATH, FIDO, PIV, OpenPGP, and both OTP slots, each with confirmation.

### Why OTP slot contents can't be fully read back

YubiKeys never return a slot's secret or stored configuration. The desktop app only shows "Programmed" / "Empty". This app shows everything the hardware does report, and identifies the type from the slot's behavior.

### Not yet included

YubiHSM Auth, Ed25519/X25519 PIV keys, OpenPGP key generation/import (use GnuPG).

## Recipes (batch provisioning)

`yubi/RecipeFeature.kt` + `ui/RecipesScreen.kt`. A recipe is an optional set of: OTP slot 1/2 programs or deletes, FIDO2 PIN, OATH password, PIV PIN/PUK/management key (fixed or random per key), OpenPGP user/admin PINs, and device configuration (USB/NFC applications, timeouts, lock code). Credentials are only applied where the key still has factory defaults, so re-running a recipe on an already-provisioned key is harmless.

Flow: **Preview changes** reads the connected key and lists each step as apply / skip / warning with current vs planned values → **Apply** shows a confirmation listing exactly what will be written → steps run in order (slots, FIDO, OATH, PIV, OpenPGP, then device config last because it may reboot the key) → a result dialog shows per-step outcome and any generated secrets. Plug in the next key and preview again. Every application is appended to a session batch log exportable as CSV (`Export batch log`), which is the only place generated OTP secrets, passwords and management keys are kept.

Recipes are stored in `files/recipes.bin`, AES-256-GCM encrypted with a key in the Android Keystore (`RecipeStore`).

## Supported keys

Device detection uses YubiKit's `support` module (`DeviceUtil.readInfo`), which works for every generation. The drawer and Home screen only show screens whose application the connected key supports (`Screen.availableFor`).

| Key | What is available |
|---|---|
| YubiKey 5 / 5 FIPS / 5 CSPN | Everything |
| YubiKey Bio Multi-protocol | Passkeys, Fingerprints, Certificates, Applications, Reset (incl. device-wide) |
| YubiKey Bio FIDO Edition | Passkeys, Fingerprints, Applications, Reset |
| Security Key series | Passkeys, Applications (NFC toggle), Reset |
| YubiKey 4 | Accounts, Certificates, OTP slots, OpenPGP, Applications (USB mode), Reset |
| YubiKey NEO | Same as YubiKey 4 (U2F only, no passkey management) |
| YubiKey Standard / Edge / Nano (fw 2.x) | OTP slots only |

Fingerprint enrollment requires USB (the sensor is not powered over NFC). Behaviour on firmware 2.x/3.x follows YubiKit's documented support but has not been verified on physical hardware.

## Passkey provider (Android 14+)

Android's built-in support for security keys is unreliable for YubiKey passkeys: it often can't ask for the FIDO2 PIN over NFC or find passkeys stored on the key, so sign-ins fail. YK Manager v2.15 adds its own passkey provider for Android Credential Manager, the same approach Yubico's YubiKey Passkey Enabler uses. When a site or app asks for a passkey, Android shows "YubiKey" as an option and YK Manager runs the whole ceremony with your key.

Setup:

1. Open **Passkeys** in YK Manager and tap **Turn on** in the "Passkey provider" card (or go to Settings > Passwords, passkeys & accounts and enable YK Manager).
2. In Chrome, open Settings > Autofill services and choose **Autofill using another service**, then restart Chrome. Without this, Chrome uses only Google Password Manager.
3. Sign in or register on a site, pick **YubiKey** in the Android sheet, then tap or plug in the key. Enter the FIDO2 PIN when asked and touch the key when it blinks.

What it handles:

- PIN entry before the tap, so one NFC tap does PIN and signing together. Over USB the app checks first whether a PIN is needed.
- Several passkeys for the same site: you get a picker. Account names are remembered on the phone (optional, can be cleared) so they appear in the Android sheet next time.
- Keys with no PIN: "preferred" verification proceeds without one; if a site requires verification, the app offers to set a PIN. A key that demands a PIN change gets a change-PIN prompt.
- Calls from browsers (checked against Google's list of privileged browsers) and from native apps (checked against the site's Digital Asset Links file).
- Older U2F-only keys (YubiKey 4, NEO) for sites that allow non-discoverable credentials.

Sign-in responses follow what Android expects from a passkey provider: `authenticatorAttachment` is reported as `platform` (as Google Password Manager, Bitwarden and KeePassDX do), `clientExtensionResults` is always present, and for browser requests the app rebuilds the browser's exact `clientDataJSON` and returns it when its hash matches the one that was signed (otherwise a placeholder the browser replaces).

Registration responses use attestation "none" unless the site asks for more, and the provider declares both the platform and androidx passkey capability types. See [release notes](docs/RELEASE-NOTES.md) v2.19 and v2.20 for the details. After updating from v2.18 or earlier, switch YK Manager off and on in Settings > Passwords, passkeys & accounts so Android re-reads its capabilities.

If YK Manager never appears in the sign-in sheet (v2.17):

- The Passkeys card shows whether YK Manager is on as a provider, and whether each installed Chrome channel has **Autofill using another service** on (read from Chrome's `AutofillThirdPartyModeContentProvider`; this is why the app declares `READ_USER_DICTIONARY`, which Chrome requires to read that one setting). A button opens Chrome's setting directly. Clearing Chrome's data resets it.
- **Test passkey sheet** sends a test request (site `selftest.ykmanager.invalid`) through Android's Credential Manager. If a "YK Manager test" entry appears, Android and the provider are fine and any remaining problem is the browser's routing. If Android never asks YK Manager, the provider isn't enabled.
- The diagnostics log now also records every time Android asks YK Manager for a passkey (caller, site, number of `allowCredentials`, entries offered) and any error while building the entries.

Diagnostics: the Passkeys screen keeps a log of the last 15 passkey requests (site, caller, requested options, returned flags and fields; no PINs, challenges, signatures or keys). Use Copy log when a site rejects a sign-in.

Network use: the provider is the only feature that needs the INTERNET permission. `READ_USER_DICTIONARY` is used only to read Chrome's autofill setting; the user dictionary is never read. It downloads Google's privileged browser list (bundled copy, refreshed at most daily) and, for native app callers, `https://<site>/.well-known/assetlinks.json`. No user data is sent.

Status: the CTAP flow is covered by JVM unit tests against a software authenticator (`app/src/test/.../SoftAuthenticator.kt`), but it has not yet been tested on a phone with a real YubiKey.

## How NFC actions work

USB-connected keys act immediately. For NFC, start an action in the UI and the app will ask you to **hold the YubiKey against the phone's NFC reader** — the action then runs during that tap (keep the key on the reader until the progress bar disappears). This mirrors how Yubico Authenticator works on Android.

## Requirements

- Android 7.0+ (API 24) phone
- Any YubiKey (NFC model for tap-to-connect, or any USB key + OTG adapter); see Supported keys
- To build: Android Studio (latest), JDK 17. The project uses AGP 9.5.1 (built-in Kotlin, 2.4.21), Gradle 9.9, compileSdk 37 / targetSdk 36, YubiKit 3.3.0.

## Build & install

Run unit tests with `./gradlew :app:testDebugUnitTest`.

```bash
./gradlew :app:assembleDebug      # debug build
./gradlew :app:assembleRelease    # signed release build
adb install app/build/outputs/apk/release/app-release.apk
```

Or open the folder in Android Studio and press Run. A pre-built release APK is included alongside the source zip (`YubiKeyManager-v2.0-release.apk`).

### Release signing

Release builds are signed with a local keystore that is **not** stored in this repository (`.gitignore` excludes `*.jks`, `*.keystore` and `keystore.properties`):

- Keystore: `keystore.jks` (alias `ykmanager`)
- Credentials: `keystore.properties` at the project root with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`
- `app/build.gradle.kts` picks these up automatically for the `release` build type, and falls back to the debug key if `keystore.properties` is missing.

Keep `keystore.jks` safe — Android only installs an update over an existing install if it is signed with the same key. For a personal app this self-signed setup is fine; if you ever publish to Play you'd use Play App Signing.

### CI/CD (GitHub Actions)

| Workflow | Trigger | What it does |
|---|---|---|
| `android-ci.yml` – Android CI | push to `main`, pull requests | Unit tests, debug APK, release-variant compile check; APK and test reports as workflow artifacts |
| `release.yml` – Release build | tag `vX.Y` (or manual run) | Signed AAB + APK, signature check, SHA-256 sums, GitHub Release; optional Google Play upload |
| `security.yml` – Security scans | push, PR, weekly | OSV-Scanner SCA, Semgrep SAST, Android Lint |

See [docs/CI-CD.md](docs/CI-CD.md) for the required secrets and the release procedure.

## Project layout

```
app/src/main/java/net/skorchin/ykmanager/
├── MainActivity.kt          # Lifecycle, NFC reader mode / NDEF intents, USB discovery wiring
├── passkey/                 # Credential Manager passkey provider (service, activity, ceremony, JSON, diagnostics)
├── yubi/
│   ├── YubiKeyCore.kt       # Discovery, connections, sessions, busy/touch/dialog state
│   ├── AppViewModel.kt      # Screens, feature wiring, USB auto-refresh, inventory
│   ├── NfcTapHandler.kt     # What an NFC tap does when nothing is waiting (Settings → NFC tap)
│   ├── OtpFeature.kt        # OTP slots API and slot identification, delegating to:
│   │   ├── OtpNdef.kt       #   NFC (NDEF) tag reading/decoding and NDEF output configuration
│   │   ├── OtpSlotProgrammer.kt  # program / delete / swap slots, HMAC challenge-response
│   │   ├── OtpOutputUpdater.kt   # Update settings on a programmed slot
│   │   ├── OtpCodec.kt      #   pure encoding/decoding (output classification, access codes, NDEF config)
│   │   └── OtpModels.kt     #   slot types, history, shared slot state
│   ├── NdefReader.kt, OtpDecoder.kt, OtpOutput.kt, Encoding.kt  # parsing and encoding helpers
│   └── OathFeature.kt, FidoFeature.kt, PivFeature.kt, OpenPgpFeature.kt, ManagementFeature.kt,
│       RecipeFeature.kt, Inventory.kt, ...                       # one class per YubiKey application
└── ui/                      # Compose screens, one file per section, plus shared Components.kt
```

All YubiKey communication goes through `YubiKeyCore`, which runs it on a single-threaded dispatcher, as YubiKit requires (only one connection can be open at a time). Unit tests live in `app/src/test/` and run on the JVM; classes that talk to Android or the key keep their decisions in plain functions or behind small interfaces (for example `NfcTapHandler.Effects`) so they can be tested without hardware.

## Security notes

- Think twice before disabling an application over the interface you are currently using — e.g. disabling YubiOTP over NFC while managing slots over NFC. The desktop manager warns about the same thing.
- Slot configurations cannot be read back out of the key: if you program a random Yubico OTP credential, save the parameters shown after programming if you intend to register them with a validation server.
- See [docs/SECURITY-REVIEW.md](docs/SECURITY-REVIEW.md) for the security review and the scanners that run in CI.

## Roadmap ideas

- YubiHSM Auth
- Ed25519/X25519 PIV keys
- OpenPGP key generation and import
- Hardware testing of the passkey provider across YubiKey generations

## Sources

- YubiKit Android SDK overview: https://developers.yubico.com/yubikit-android/index.html
- Android module (YubiKitManager, discovery, connections): https://developers.yubico.com/yubikit-android/android/
- YubiOTP module (slot configuration): https://developers.yubico.com/yubikit-android/yubiotp/index.html
- Management module (device info, device config): https://developers.yubico.com/yubikit-android/JavaDoc/management/latest/com/yubico/yubikit/management/ManagementSession.html
- YubiKit on Maven Central: https://repo1.maven.org/maven2/com/yubico/yubikit/

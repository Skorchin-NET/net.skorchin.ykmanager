# Release notes

Each `## vX.Y` section is copied into the GitHub Release body by `.github/workflows/release.yml`. Changes merged since the last release collect under `## Unreleased` until the next batched release (see [CI-CD.md](CI-CD.md#release-cadence-batch-by-area)).

## Unreleased

## v2.42 (versionCode 44)

- PIN entry now opens the number keypad: FIDO2 PIN (including the passkey sign-in sheet),
  PIV PIN and PUK, OpenPGP user PIN, admin PIN and reset code, and the PIN fields in Recipes.
  YubiKey PINs may contain letters, so each PIN field has a keyboard button to switch to the
  full keyboard and back. Passwords and secret keys still use the full keyboard

## v2.41 (versionCode 43)

- Factory reset moved from the side menu to a "Factory reset" section at the bottom of Settings.
  The reset cards and the typed RESET confirmation are unchanged

## v2.40 (versionCode 42)

- Printed inventory reports now have a 0.75 in margin on all four sides. v2.39 set the margin
  in the report's stylesheet, which Android's print system ignores, so text still reached the
  page edge. The margin is now set on the print job itself
## v2.39 (versionCode 41)

- Printed inventory reports now have page margins (18 mm top, 16 mm sides, 20 mm bottom).
  Previously Android's "Save as PDF" printer put text at the edge of the page
- Print layout tidied: a header rule under the title, shaded alternating rows, indented
  sub-groups, long values (fingerprints, serials) wrap inside the cell, and rows and headings
  are kept from splitting across pages
- Internal: OtpFeature split into OtpNdef (NFC tag reading and decoding), OtpSlotProgrammer
  (programming, delete, swap, HMAC), OtpOutputUpdater (output settings) and OtpCodec (pure
  encoding/decoding); no behaviour change
- Internal: NFC tap handling moved out of AppViewModel into NfcTapHandler; no behaviour change
- Unit tests for the NFC tap actions, OTP output classification, access codes, NDEF
  configuration records and slot-type evidence
- Build: GitHub Actions updated (checkout v7, setup-gradle v6, upload-artifact v7,
  download-artifact v8, OSV-Scanner 2.7.1); build-tool dependencies pulled in by the Android
  Gradle plugin raised to patched versions
- Docs: README feature list updated (Full inventory, pinned accounts, PIN-protected PIV
  management key), passkey fix history moved here, release cadence documented

## v2.38 (versionCode 40)

- Passkey registration now honors a site's choice of authenticator. When a site asks for a passkey
  stored in the phone itself (authenticatorAttachment "platform"), YK Manager no longer appears in
  Android's save sheet and nothing is written to the YubiKey. The check is repeated before the key is
  used, so an old sheet entry can't bypass it
- New option in Passkeys: "Offer the YubiKey when a site asks for a phone passkey" (off by default)
  to try saving on the YubiKey anyway; the site may still refuse it
- New passkeys are now reported as "cross-platform" (a roaming security key) instead of "platform",
  except when the user answers a platform-only request with that option on. Sign-in responses are unchanged
- The passkey log notes when a platform-only registration was skipped

## v2.37 (versionCode 39)

- Full inventory (new screen): one read collects every application the key supports (device,
  OTP slots, accounts, FIDO2/passkeys, PIV certificates, OpenPGP) into a single report. Print it
  or save it as a PDF through Android's print dialog, or share it as text. It lists configuration
  only, never secrets, PINs or codes
- Passkeys → Security settings (firmware 5.4+): turn Always require user verification on or off,
  raise the minimum PIN length (optionally shared with named websites) and force a PIN change
- Certificates → Attest: proves a key was generated on the YubiKey (firmware 4.3+) and saves the
  attestation certificate with the F9 intermediate as PEM
- Certificates → Move key to another slot, with or without its certificate, and Delete the private
  key only, keeping the certificate (firmware 5.7+)
- Management key → Protect with PIN: stores a random management key on the YubiKey, unlocked by
  the PIN, in the same format as ykman and Yubico Authenticator (legacy PIN-derived keys also work)
- Accounts → Pin to top: pinned accounts are starred and listed first (stored on this phone)
- Unit tests for the PIN-protected key objects and inventory rendering/escaping

## v2.36 (versionCode 38)

- Static passwords over NFC are now decoded to readable characters when copied (NFC tap → Copy),
  shown (Show NFC output, NFC tap → Show) or read on the OTP slots screen. YubiKit stores static
  passwords zero-padded to 38 bytes. Over USB the key types nothing for the padding, but over NFC
  it is part of the record, so the bytes weren't recognised as scan codes and the raw codes were
  copied
- Copy never puts unreadable control characters on the clipboard; it reports the problem instead
- Regression tests for padded passwords after the Yubico URI, a known text prefix and no prefix

## v2.35 (versionCode 37)

- OTP slots → Read over NFC on the slot the NFC tag doesn't send: the key's NFC (NDEF) tag only
  carries one slot (slot 1 by default), so reading slot 2 returned slot 1's OTP. The app now
  switches the NFC output to that slot for the read, keeping the same prefix and type, and switches
  it back, all in one tap. You can choose to leave it on the new slot, and enter an access code if
  one is set
- The app remembers which slot the NFC output sends, per key, and shows it on the OTP slots screen.
  The key can't report this, so until it is set in the app it is assumed to be slot 1
- A static password with an unrecognised prefix is never rewritten; the app asks you to set the NFC
  output instead

## v2.34 (versionCode 36)

- OTP slots → Read over NFC: fixed "Failed to read the NFC (NDEF) output. NDEF data missing or
  invalid". The button relied on the NDEF message Android caches during its own tag check, which
  the app turns off so Android doesn't open the OTP link itself. It now reads the tag directly,
  like the OTP decoder and Show NFC output already do
- Static passwords read this way are decoded from keyboard scan codes before the slot type is
  identified

## v2.33 (versionCode 35)

- NFC tap set to "Copy" or "Show the NDEF one-time password": a key whose NDEF output is a text
  record (text mode or a static password) no longer brings up Android's "New tag scanned" box
  with raw keyboard scan codes when YK Manager isn't in front. The tap now goes to YK Manager,
  which decodes it
- Copy: the password is copied, a short confirmation appears, and you return to the app you were
  using. Show: YK Manager opens with the decoded password
- Ordinary (non-YubiKey) NFC text stickers are ignored, and with any other tap action text tags are
  left to Android as before

## v2.32 (versionCode 34)

Maintenance release: code quality and dependency updates, no feature changes.

- Updated AndroidX Activity 1.13.0, Lifecycle 2.11.0, Core KTX 1.19.1 (now declared explicitly),
  Bouncy Castle 1.87 and Gradle 9.9.1
- PIV key generation uses YubiKit's current `generateKey` API instead of the deprecated one
- Kotlin idioms: `entries` instead of `values()`, KTX `SharedPreferences.edit {}`, `toUri()`,
  `Bitmap.scale()`, primitive `mutableIntStateOf` state, removed redundant casts and `!!`
- Themed (monochrome) launcher icon for Android 13+; round icon declared
- Kotlin compiler warnings 7 → 0 (two intentional, documented suppressions); Android Lint warnings 49 → 14 (the rest are legacy icon artwork or reviewed false positives)

## v2.31 (versionCode 33)

- OTP slots → OTP decoder: decrypt a Yubico OTP with the slot's AES key to see the private ID,
  usage and session counters, timer and random field; the checksum confirms the key is right
- Capture the OTP by touching the key (USB), Read over NFC, or paste an OTP or my.yubico.com link
- Optional expected private ID check; serial number shown when the public ID is serial-based
- Runs entirely on the phone; the key and results are never stored

## v2.30 (versionCode 32)

- Show NFC output: static passwords are now decoded correctly. Scan codes for digits 3–0,
  space, Tab, Enter and most punctuation are also printable bytes (some equal '#', '/', '='),
  so passwords containing them were split from the prefix in the wrong place and shown garbled
- The prefix is matched exactly when it is Yubico's default or one this app wrote; otherwise
  a stricter check is used
- Backslash and pipe are programmed with the standard US scan code (0x31); passwords
  written by older versions (0x32) still decode correctly
- The dialog lists the raw scan codes so the translation can be checked

## v2.29 (versionCode 31)

- Settings → OTP output: the Settings tab of the Windows YubiKey Personalization Tool —
  Tab before/after first part/after OTP, Enter, 0.5 s delays, output speed throttling
  (10/20/30 ms pacing), numeric keypad, fast trigger, send reference string, serial number
  visibility (button at startup, USB descriptor, API), invert LED and allow update
- Live preview of what a touch types; settings apply to every Yubico OTP, OATH-HOTP and static
  password slot the app programs
- OTP slots: "Update settings" rewrites a programmed keyboard slot's output settings without
  replacing its credential (requires Allow update, firmware 2.3+)
- Recipes: each slot step stores the output settings in effect when it was added
- Toolchain: AGP 9.5.1 with built-in Kotlin, Gradle 9.9, Compose BOM 2026.09, compileSdk 37
  (targetSdk stays 36); YubiKit 3.1.0 → 3.3.0 (new "FIDO over smart card" capability shown)

## v2.28 (versionCode 30)

- Passkeys: tap a passkey to see everything the YubiKey stores about it — relying party
  (ID, name, RP ID hash), user account (name, display name, user handle in hex/base64url/text),
  credential ID (hex and WebAuthn base64url), transports, credProtect policy, large-blob key
  presence, and the public key (algorithm, curve, coordinates, SHA-256 fingerprint)
- Each value can be copied individually or all at once; delete is available from the details view
- Credential enumeration now reads the full CTAP 2.1 response (credProtect, largeBlobKey)
- Dependency updates merged via Dependabot: Kotlin 2.4.21, coroutines 1.11.0, GitHub Actions

## v2.27 (versionCode 29)

- Security review: SCA (OSV), SAST (Semgrep) and Android Lint; see `docs/SECURITY-REVIEW.md`
- Bouncy Castle 1.78.1 → 1.86 (7 advisories closed); dependency lockfile added
- Backup/device-transfer exclusion rules for the encrypted OATH key store
- Copied codes and passwords are marked sensitive on the clipboard
- NFC "open link" accepts only http(s) URLs
- GitHub Actions: security scans, Dependabot, CI and release pipelines

## v2.26 (versionCode 28)

- NFC taps no longer leave YK Manager for the system NDEF handler
- Slots: "Show NFC output" and an opt-in switch to let the phone handle taps

## v2.25 (versionCode 27)

- "Remember on this phone" for OATH passwords now persists (Android Keystore, AES-GCM)
- "Forget password" button; remembered keys survive key removal

## v2.24 (versionCode 26)

- "QR from image" import fixed; clearer error messages

## v2.23 (versionCode 25)

- Static passwords delivered over NDEF are decoded from HID scan codes

## v2.22 (versionCode 24)

- QR scanning via the Google code scanner with ZXing fallback
- Import from Google Authenticator export QR codes

## v2.20 (versionCode 22)

- Passkey registration responses that sites and Chrome accept. When a site asks for attestation
  "none" (or doesn't specify), the response carries fmt "none" with a zero AAGUID, exactly as
  Chrome returns for security keys; many bank servers accept only "none". "direct", "indirect"
  and "enterprise" keep the YubiKey's own attestation
- `authenticatorData`, `publicKey` (DER SubjectPublicKeyInfo, named curve) and
  `publicKeyAlgorithm` are derived from the returned attestation object, because Chrome rejects a
  provider's registration if any of them differs from what it computes
  (`MakeCredentialResponseFromValue` in Chromium's `components/webauthn/json/value_conversions.cc`).
  `RegistrationResponseTest` covers both
- The diagnostics log records the requested and returned attestation format

## v2.19 (versionCode 21)

- Declare the passkey capability Android actually matches. The provider declared
  `android.credentials.TYPE_PUBLIC_KEY_CREDENTIAL`, but requests made through androidx.credentials
  (Chrome and apps) are typed `androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL`. Android only asks
  providers whose capabilities match, so YK Manager was never asked, even as the default passkey
  service. Both spellings are now declared (`ProviderCapabilityTest` guards this)
- After updating, switch YK Manager off and on in Settings > Passwords, passkeys & accounts so
  Android re-reads it

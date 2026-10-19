# cordova-plugin-android-fingerprint-auth — Danish Crown BiometricPrompt fork (1.6.0-dc.1)

This is a drop-in replacement for `cordova-plugin-android-fingerprint-auth` 1.5.0 (mjwheatley), which the Ejer app uses.

## Why

Version 1.5.0 uses `FingerprintManager` with its own dialog, and Google deprecated `FingerprintManager` in Android 9. Phones with an under-display fingerprint sensor or face unlock don't work reliably with it. There are two likely causes; neither has been confirmed on a real phone, because the emulators only have a rear sensor:

- the plugin's own dialog appears, but the sensor never starts;
- `FingerprintManager` is missing (`getSystemService` returns null on face-only phones), so the plugin throws a NullPointerException inside `execute()`. Cordova swallows the exception and never calls back.

Either way the app waits forever, which matches the reported symptom: "Opret pinkode → Bekræft → nothing happens".

The fork uses `androidx.biometric` `BiometricPrompt`, the system biometric dialog. It works on every sensor type and on Class 3 face unlock. Every action ends in exactly one success or error callback, including on unexpected exceptions.

## What stays the same (no Neptune code changes)

- The plugin id is `cordova-plugin-android-fingerprint-auth`, the JS module is unchanged (`www/FingerprintAuth.js` is copied verbatim), the global is `window.FingerprintAuth`, and the Java feature/class name is unchanged.
- `isAvailable(success, error)` returns `{isAvailable, isHardwareDetected, hasEnrolledFingerprints}`. It now also returns `biometricStatus`, the raw `BiometricManager` code, for debugging.
- `encrypt(config)` returns `{withFingerprint: true, token}`. With `encryptNoAuth` it returns `{withBackup: true, token}`.
- `decrypt(config)` returns `{withFingerprint: true, password}`.
- `delete({clientId, username})` and `dismiss()` work as before.
- The error strings are the same as before (`FINGERPRINT_CANCELLED`, `FINGERPRINT_ERROR`, `INIT_CIPHER_FAILED`, …).
- Config fields used: `clientId`, `username`, `password`, `token`, `userAuthRequired`, `locale`, `dialogTitle`, `dialogMessage` and `encryptNoAuth`. `disableBackup` and `maxAttempts` are accepted but ignored (see below).

## Key compatibility / migration

The fork reads and writes exactly the same data as 1.5.0:

| | 1.5.0 | fork |
|---|---|---|
| Keystore key | AES/CBC/PKCS7, alias = `clientId`, `setUserAuthenticationRequired(userAuthRequired)` | same alias and spec; an existing key is reused |
| IV | SharedPreferences file `clientId+username`, key `aes_iv` | same |
| Token | Base64(AES(`clientId+username` + `|:|` + password)) | same |

What this means for users after the update:

- **Users who have biometrics enabled and whose phone worked before** keep working. Their existing token is decrypted with the existing key, and they don't need to re-enable anything. This was verified with a 1.5.0 build upgraded in place to the fork; see the test log.
- **Users whose biometrics never worked** (new phones) can now enable them. They simply enable biometrics as usual.
- **The key is invalidated** when the user adds a new fingerprint or removes the lock screen. This is the same as before. `decrypt` returns `INIT_CIPHER_FAILED`, as 1.5.0 did, and the app falls back to the PIN. The fork also deletes the dead key. When the user next enables biometrics, `encrypt` creates a fresh key. Before, enrolling again failed until the key was deleted.
- One improvement: the IV is now stored only after a successful encrypt. With 1.5.0, cancelling the "enable biometrics" prompt overwrote the IV and silently broke the token the app already had.

Worst case, a user has to re-enable biometrics: switch it off, then on again, or create a new PIN. The PIN login is never affected.

## Behaviour differences

- The dialog is the system `BiometricPrompt` dialog. Its title comes from `dialogTitle`, or otherwise "Biometrisk godkendelse" / "Biometric authentication" / German / Swedish, chosen by `locale`. `dialogMessage` is the description, and the button text is "Annuller"/"Cancel". The fork no longer changes the whole app's locale; 1.5.0 called `Resources.updateConfiguration`.
- Only **Class 3 (strong)** biometrics are allowed, because the token is bound to a crypto key. On phones where face unlock is Class 2 (many Samsung and older devices), the prompt offers the fingerprint sensor only. On Pixel 8 and newer, face unlock is Class 3 and works.
- `disableBackup: false` (PIN/pattern fallback) is not offered. A per-use crypto key cannot be unlocked by a device credential on Android < 11. The Ejer app always sends `disableBackup: true`.
- `maxAttempts` is ignored. The system locks out after 5 failed attempts, and 1.5.0 also ignored the value 5 that the app sends.
- The system decides the number of attempts. Errors such as lockout are reported as `FINGERPRINT_ERROR`, and cancel/back as `FINGERPRINT_CANCELLED`.

## Build requirements

- cordova-android ≥ 10. CordovaActivity must be an AppCompat/FragmentActivity. Tested with 15.1.0, compileSdk/targetSdk 36 and minSdk 24.
- Adds `androidx.biometric:biometric:1.1.0`. Override it with the plugin variable `ANDROIDX_BIOMETRIC_VERSION`.
- Permissions: `USE_FINGERPRINT` and `USE_BIOMETRIC`. Both are normal permissions with no runtime prompt.

## Shipping it through Neptune

Neptune Cockpit (Mobile Client → Plugins tab) installs plugins from `config.xml` `<plugin>` entries. Two options:

1. **Git (recommended)**: the repository is https://github.com/factors-dc/dc-fingerprint-auth, and each release is a tag (first: `v1.6.0-dc.1`). In the Plugins tab, replace the `cordova-plugin-android-fingerprint-auth` entry (source npm) with:
   ```xml
   <plugin name="cordova-plugin-android-fingerprint-auth" source="git" spec="https://github.com/factors-dc/dc-fingerprint-auth.git#v1.6.0-dc.1" />
   ```
   This works the same way as the existing `cordova-plugin-file-transfer` git entry. The Neptune build service must be able to reach the repository, so it must be public or reachable with a token in the URL.
2. **npm**: publish under a scope, for example `@danishcrown/cordova-plugin-android-fingerprint-auth`. Keep the plugin **id** in `plugin.xml` unchanged. Then reference it in the Plugins tab with `source="npm" spec="@danishcrown/cordova-plugin-android-fingerprint-auth@1.6.0-dc.1"`. Note that the Cordova id and the npm name then differ. Cordova accepts that, but check that the Plugins tab does too.

Don't keep the old npm entry next to the new one, because both have the same id.

For a manual (local) build, as in this repo's `tst-bio/`:

```bash
cordova plugin rm cordova-plugin-android-fingerprint-auth
cordova plugin add /path/to/dc-fingerprint-auth
```

Rollout: TST build → testers on new phones, old phones and existing users with biometrics enabled → PRD as the next normal release (22.10.18+). It is independent of the targetSdk 36 release that is already in review.

## Testing

`test/fpcheck.mjs` drives `window.FingerprintAuth` inside a debuggable build over Chrome DevTools. It works on an emulator, where `adb emu finger touch` is available:

```bash
node test/fpcheck.mjs emulator-5554 full
```

Steps run: availability → encrypt (touch) → decrypt (touch) → cancelled encrypt (Back) → decrypt again (still the old token) → delete → decrypt after delete (error).

`test/run-device.sh <serial>` runs the whole migration check. It installs a TST build with plugin 1.5.0 and enables biometrics with it. It then upgrades in place to the fork with the same signing key and app data, decrypts the old token, and runs `full`.

### Results (2026-10-19, TST 22.10.32, cordova-android 15.1.0, targetSdk 36, emulators)

| Emulator | No fingerprint enrolled | 1.5.0 token → fork (upgrade) | Full fork run | Key invalidated by new fingerprint |
|---|---|---|---|---|
| Android 8.0 (API 26) | ✅ isAvailable false, encrypt → error callback | ✅ | ✅ | – |
| Android 15 (API 35) | ✅ isAvailable false | ✅ | ✅ | – |
| Android 16 (API 36) | – (fingerprint already enrolled) | ✅ | ✅ | – |
| Android 17 (API 37) | ✅ isAvailable false, encrypt → error callback | ✅ | ✅ | ✅ decrypt → INIT_CIPHER_FAILED, re-enable works |

The raw output is in `test/result-api*.txt`. The prompt text "Biometrisk godkendelse", the description, and "Annuller" were checked through the UI tree with `locale: 'da'`. A screenshot isn't possible because the prompt is a secure window.

The real Neptune UI flow was also checked on Android 16 (API 36) with TST login. The flow was "Opret pinkode → Bekræft" → prompt "Aktiver indlogging med fingeraftryk?" → touch, then "Login med fingeraftryk" → touch, which lands on the launchpad (`test/login-api36.png`).

Not covered: real phones with an under-display sensor or face unlock (the emulators only have a rear sensor). **Test on a tester's new phone before PRD.**

The test build in `tst-bio/` differs from a Neptune build in two places, both forced by the sandbox having no network access: `androidx.core:core:1.6.+` is pinned to 1.6.0, and file-transfer is installed from a local copy. Neither affects this plugin.

# Bitwarden fork

This is Ivan's personal fork of `bitwarden/android`. Upstream's agent guide is
`.claude/CLAUDE.md`; this file covers only what the fork adds.

## What the fork carries

- Packaging: the `fdroid` flavor is shipped as `com.colonelpanic.wardenkey`
  ("Bitwarden"), with `fdroidDebug`/`fdroidRelease` resource overrides. The
  `standard` flavor keeps upstream's ID.
- Release signing from `keystore.properties` (written by CI) and
  `.github/workflows/fork-release.yml` / `fork-ci.yml`.
- YubiKey passkey login and PRF unlock:
  - `network`: `IdentityTokenAuthModel.WebAuthn`, assertion-options endpoint,
    `WebAuthnPrfDecryptionOptionJson` in token and sync decryption options.
  - `app/.../data/auth/manager/YubiKeyPasskeyManager*`: yubikit-android CTAP2
    assertion with the PRF extension.
  - `app/.../data/auth/repository/util/WebAuthnPrfExtensions.kt`: PRF key
    stretching and key set decryption. The SDK then decapsulates the user key
    through `InitUserCryptoMethod.AuthRequest`.
  - `app/.../ui/auth/feature/passkey/`: the self-contained button, dialogs and
    ViewModel. Screens receive the buttons as slot parameters from their
    navigation destinations.
  - `app/.../data/auth/manager/WearAuthn*`: a paired watch running
    [WearAuthn](https://github.com/colonelpanic8/WearAuthn) as a
    `YubiKeyDevice` over Bluetooth RFCOMM (service UUID
    `5c1b1d3a-62a4-4f4e-9f0e-8a7c2d1e6b3f`, 4-byte length + APDU frames). The
    passkey dialog looks for it alongside NFC and USB keys.
  - Passkey picker: `KnownPasskeyManager` remembers passkeys used on this
    device (kept across logout) and names them from `GET /webauthn`, matched
    to sync's PRF options by `encryptedUserKey`. The dialog lists them and
    makes those on a connected key selectable: `findCredentials` probes the
    key with `up=false`, and discoverable logins return every credential
    (`MultipleCredentials`) instead of picking the first.

Keep fork changes small and additive so they rebase onto upstream `main`.

Work directly on `main` and push it; releases are tagged from it. Don't create
topic branches or worktrees.

## Invariants

- Never change the release signing key. It lives in `pass` at
  `signing/bitwarden-android-release` and in the repository's
  `ANDROID_KEYSTORE_*` / `ANDROID_KEY_*` secrets. Never commit keystores or
  passwords.
- The PRF output is secret: never log it or send it to the server. The
  `deviceResponse` sent to the server carries no client extension results.
- Release tags are `vYYYY.M.P-wk.N` (upstream version plus fork release
  number). The workflow derives `versionCode` from the tag.

## Checks

`nix develop` (or direnv) provides JDK 21 and the Android SDK. Gradle needs a
GitHub token with `read:packages` for the Bitwarden SDK (`GITHUB_TOKEN`, or
`gitHubToken` in `user.properties`).

- `just build`: debug APK (`:app:assembleFdroidDebug`)
- `just lint`: detekt (autocorrects formatting)
- `just test '<filter>'`: app unit tests, e.g. `'*Passkey*'`

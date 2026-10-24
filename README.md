# PhotoLock

A private photo & video vault for Android. Import your own photos and videos
into encrypted on-device storage (AES-256/GCM, key in the Android Keystore)
and open them only with your PIN or fingerprint. Nothing is uploaded anywhere.

- **Package:** `com.sowrobrana.photolock`
- **Stack:** Kotlin + Jetpack Compose, minSdk 26, targetSdk 35
- **Full product plan:** see [PLAN.md](PLAN.md)

> Current state: **Phase 1 (MVP) implemented.** Real PIN unlock (salted
> PBKDF2 verifier, brute-force backoff, break-in log), biometric unlock,
> Photo Picker import with encrypted thumbnails, Room index, folders,
> grid + viewer, MediaStore export, system-consent removal of originals,
> and auto-lock. See the phase table in PLAN.md.

## Build the APK (no PC / Android Studio needed)

The build runs in **GitHub Actions** — the phone is all you need:

1. Make sure this project is pushed to the GitHub repo
   (`sowrobrana1/photolock`, branch `main`).
2. Open the repo in your phone browser → **Actions** tab →
   **Build debug APK**. It starts automatically on every push; you can also
   start it by hand with **Run workflow**.
3. Wait for the green ✓ (a few minutes), then open the run →
   scroll to **Artifacts** → download **`photolock-debug-apk`** (a zip).
4. Unzip it — inside is `app-debug.apk`.

## Install on the Samsung phone

1. Open the Files app → tap `app-debug.apk`.
2. If Android asks, allow **"Install unknown apps"** for the Files/browser
   app just this once (*Settings → Apps → ⋮ → Special access → Install unknown
   apps* is the manual path).
3. Tap **Install** → **Open**. PhotoLock appears in the app drawer.
4. Updates work the same way: download the new artifact and install it over
   the old one (same signature, so data is kept).
   ⚠️ *Uninstalling the app deletes the vault with it — export anything
   important first (select items in the vault → Export to gallery).*

> The debug APK is for testing on your own phone only. The Play Store
> version needs a separate signed release build (AAB) — steps in PLAN.md §6.

## Project layout

```
app/src/main/java/com/sowrobrana/photolock/
├── MainActivity.kt          # FragmentActivity host (BiometricPrompt needs it)
├── PhotoLockApp.kt          # Application class — builds AppContainer
├── AppContainer.kt          # manual DI graph (crypto, db, repo, session)
├── LockSession.kt           # unlocked-session state + auto-lock
├── crypto/CryptoManager.kt  # AES-GCM + Android Keystore (streaming)
├── security/PinManager.kt   # salted PBKDF2 PIN verifier (encrypted prefs)
├── data/
│   ├── VaultRepository.kt   # import / decrypt / export / consent flows
│   ├── SettingsStore.kt     # DataStore settings + PIN back-off state
│   └── local/               # Room entities, DAOs, database (vault index)
└── ui/
    ├── navigation/          # NavHost: lock → vault → viewer / settings / log
    ├── lock/                # PIN pad: create/verify, biometric, backoff
    ├── vault/               # media grid, folders, multi-select, import
    ├── viewer/              # photo viewer (zoom) + video player (Media3)
    ├── settings/            # change PIN, biometric, auto-lock, break-in log
    ├── components/          # encrypted-thumbnail composable
    └── theme/               # Material 3 theme
```

## Local development (optional, if a PC is available later)

With Android Studio (or JDK 17 + Android SDK 35) installed:

```bash
./gradlew assembleDebug        # needs the Gradle wrapper jar — generate once
                               # with any local Gradle 8.9: `gradle wrapper`
# or, with Gradle 8.9 on PATH:
gradle assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

# PhotoLock — Product Plan

**Package:** `com.sowrobrana.photolock` · **Platform:** Android (Kotlin + Jetpack Compose) · **minSdk 26 / targetSdk 35**

PhotoLock is a private photo & video vault: the user imports their **own** photos
and videos into encrypted, app-private storage and opens them only after a PIN
or biometric unlock. It belongs to a well-established, legitimate Play Store
category (Keepsafe, Gallery Lock, and Samsung's own Secure Folder are the
reference points). Everything in this plan is built around one rule:

> **Honest app, honest listing.** PhotoLock presents itself as exactly what it
> is — a vault. No disguised icon, no fake "calculator" front, no deceptive
> name. Deceptive-behaviour policies are the #1 killer of vault apps on Play;
> we stay far away from that line.

---

## 1. MVP feature set (v1.0)

### 1.1 Unlock
- **PIN** — 4-digit (optionally 6) PIN created on first run. Only a salted
  hash is stored (PBKDF2-HMAC-SHA256, high iteration count, per-install salt).
  The PIN itself is never stored anywhere.
- **Biometric unlock** — fingerprint/face via `androidx.biometric`
  (BiometricPrompt), offered when the device supports it and the user opts in.
  Requires the host activity to be a `FragmentActivity` (noted in code).
- **Auto-lock** — vault re-locks when the app goes to background (configurable
  timeout: immediately / 1 min / 5 min). The back stack is cleared so Back
  never returns to unlocked content.
- **Failed-attempt backoff** — after 5 wrong PINs, 30 s lockout, doubling on
  repeat. Prevents casual brute force by someone holding the phone.

### 1.2 Import
- System **Photo Picker** (`PickMultipleVisualMedia`) is the primary path —
  on Android 13+ it needs **no storage permission at all**.
- Each selected item is streamed through `CryptoManager` (AES-256/GCM,
  Android Keystore key) into `files/vault/<uuid>.pv`. Streaming only —
  large videos never sit in memory or on disk in plaintext.
- A small encrypted thumbnail is generated at import so the grid never has
  to decrypt full-size media.
- Metadata (name, MIME, size, duration, folder, import date) goes into a
  Room index next to the blobs. **Plaintext media is never written to disk.**
- After a successful import the user is *asked* whether to remove the
  originals from the gallery — see §3 for the consent flow. Default = keep
  originals. Nothing is ever deleted silently.

### 1.3 Vault browsing
- Grid of thumbnails (decrypted in memory only), folders, sort by date/name.
- **Folders** — create / rename / delete (items fall back to root), move items
  between folders.
- **Viewer** — full-screen photo viewer (zoom/pan) and video player, both fed
  from decrypted streams. FLAG_SECURE on viewer + grid so vault content does
  not leak into screenshots or the recents thumbnail.
- Multi-select: export, move, delete (deleting a vault item deletes only the
  encrypted blob — with a confirmation).

### 1.4 Export / restore
- Any item (or selection, or whole vault) can be exported back to the shared
  gallery: decrypt → `MediaStore` insert into `Pictures/PhotoLock` /
  `Movies/PhotoLock` using the `IS_PENDING` flow. Always user-initiated,
  always visible in the normal Gallery afterwards.

### 1.5 Break-in attempt log (on-device ONLY)
- Every failed unlock records: timestamp, attempt count. Optional (user
  setting, default ON): a front-camera snapshot at the moment of failure,
  stored **encrypted inside the vault** like any other item.
- The log is viewable/clearable from Settings after a successful unlock.
- **It is never uploaded, synced, or shared — there is no server.** This is
  stated verbatim in the Play listing and privacy policy.

### 1.6 Explicitly OUT of MVP scope
- Cloud backup/sync of vault contents (would create data-safety and trust
  obligations; also contradicts "on-device only").
- Disguised icon / decoy mode / fake crash screens — **policy risk, will not
  build** (see §4).
- Sharing directly from the vault (export-then-share is the supported path).

---

## 2. Encryption design

| Piece | Choice |
|---|---|
| Algorithm | AES-256/GCM (AEAD), 128-bit tag |
| Key storage | Android Keystore, alias `photolock_vault_key_v1`, hardware-backed where available; key never leaves the Keystore |
| File format v1 | `[1-byte IV length][IV][ciphertext+tag]`, streamed in 64 KiB chunks |
| PIN storage | Salted PBKDF2 hash only — the PIN is an *app gate*, it does not derive the file key |

Design notes & hardening (Phase 2):
- **Chunked AEAD**: migrate to a segmented format (per-segment IV/tag) so a
  tampered segment fails deterministically and huge files can be
  range-decrypted for video seeking. v1 format must keep a version byte
  path open.
- **Key loss = data loss.** If the user clears app data, resets the phone, or
  the Keystore key is invalidated (e.g. lock-screen reset on some OEMs), the
  vault is unrecoverable. The first-run flow must explain this plainly and
  recommend periodic export. This is the same trade-off every serious vault
  app makes; hiding it would be dishonest.
- `allowBackup="false"` in the manifest: encrypted blobs must not drift into
  cloud/device backups where they'd be useless-but-alarming baggage.

---

## 3. Android 11+ scoped-storage approach

Target devices run Android 11–15 (user's phone: Samsung S23 Ultra). Rules:

1. **App-private storage needs no permission.** All vault blobs and the index
   live under `filesDir` — always accessible, invisible to other apps and to
   the gallery.
2. **Reading originals:**
   - Primary: Photo Picker (API 33+ native; backported via AndroidX) — zero
     permissions, user picks exactly what enters the vault.
   - Fallback browsing (if we add an in-app gallery browser later):
     `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` (API 33+),
     `READ_EXTERNAL_STORAGE` capped at `maxSdkVersion=32`. Already declared
     in the manifest with that cap.
   - We will **never** request `MANAGE_EXTERNAL_STORAGE` (all-files access) —
     it is restricted on Play and unnecessary for this app.
3. **Deleting originals after import — consent only:**
   - API 30+: build a `MediaStore.createTrashRequest` (recoverable, 30-day
     trash) or `createDeleteRequest` PendingIntent and launch it; the
     **system** shows the consent dialog naming the files. The app cannot
     bypass it and must not try.
   - API 29: delete via `ContentResolver.delete`; on
     `RecoverableSecurityException`, launch its `userAction` intent for
     consent, then retry.
   - API 26–28: legacy flow under the storage permission the user granted.
   - The import flow copies + verifies *first* (decrypt-check the blob),
     and only then offers deletion. A failed import deletes nothing.
4. **Export:** `MediaStore` insert with `RELATIVE_PATH` +
   `IS_PENDING=1` → stream plaintext in → `IS_PENDING=0`. No permission
   needed for app-created media on API 29+.

---

## 4. Play policy notes (read before writing the listing)

- **Deceptive behaviour policy:** the store listing, icon, and app name must
  describe a vault app. No disguised icon, no "calculator" skin, no fake
  error screens, no hiding the app from the launcher. (Many vault apps offer
  disguise features; they are also the ones that get suspended. We don't.)
- **Data safety section:** *No data collected, no data shared.* True by
  architecture — the app has no backend and the MVP contains **no analytics
  and no network calls except AdMob's SDK** (see §5; AdMob data must be
  declared exactly as Google requires when ads ship).
- **Privacy policy:** required (the app handles personal media + uses
  biometrics APIs). Must state: media is encrypted on-device only, never
  uploaded; break-in log is on-device only; how key loss affects data;
  contact email. Host it on a public URL (the developer's existing sites
  can host it, like the ChakriBarta policy).
- **Permissions declarations:** biometric + media permissions are
  self-explanatory for a vault; keep the permission set minimal (already the
  case) to sail through review.
- **Families policy:** not a kids app; set target audience 18+/"everyone"
  appropriately (a vault is a general utility — declare "not designed for
  children").
- **Account-level risk:** this ships on the same developer account as the
  developer's other apps. A policy strike hits the whole account — another
  reason the honest-listing rule above is non-negotiable.

---

## 5. Monetisation

- **AdMob**, added in Phase 3 (after the vault flow is solid — ads must never
  complicate the first-run or unlock experience):
  - Banner or native ads **only** in low-sensitivity places: Settings screen,
    folder list footer.
  - **Never** on the lock screen, **never** in the photo/video viewer, never
    over private thumbnails. (Also good policy hygiene: no ads adjacent to
    "unlock your private photos" moments.)
- **Later (v1.5+)**: one-time premium unlock — remove ads, unlimited folders,
  6-digit PIN + auto-lock options. One-time purchase, not subscription:
  simpler trust story for this audience.
- Honest expectation (same math as the developer's other apps): BD traffic is
  low-CPM; PhotoLock earns slowly but utility apps retain well and the vault
  category has steady organic search demand ("photo lock", "gallery vault").

---

## 6. Build & release steps

### Now — debug APK via GitHub Actions (no local SDK needed)
1. Push this project to GitHub (`sowrobrana1/photolock`).
2. `.github/workflows/build-debug.yml` runs on every push to `main` (and on
   demand via *Actions → Build debug APK → Run workflow*): JDK 17 →
   Android SDK 35 → Gradle 8.9 → `gradle assembleDebug`.
3. Download the `photolock-debug-apk` artifact, unzip, install
   `app-debug.apk` on the test phone (steps in README.md).
4. Debug builds are signed with the auto-generated debug key — fine for
   on-device testing, **not** for Play.

### Later — Play release
1. Create a release keystore **once**, store it outside the repo (owner's
   Notes/password manager) — losing it means losing update access to the app.
   Enrol in Play App Signing.
2. Bump `versionCode`/`versionName`; build `bundleRelease` (AAB) in a release
   workflow (keystore injected from GitHub Secrets — never committed).
3. Play Console (same developer account): create app → listing (honest vault
   description, screenshots of the *empty/demo* vault only) → privacy policy
   URL → Data safety: no data collected/shared → content rating → target
   audience.
4. **Closed testing**: new personal developer accounts must run a closed
   test with **12+ testers for 14 days** before production (same as the
   developer's other apps — the same tester pool can run in parallel).
5. Apply for production → review → staged rollout.

---

## 7. Implementation phases

| Phase | Contents | Status |
|---|---|---|
| **0 — Scaffold** | Gradle/Compose project, NavHost skeleton (Lock / Vault / Settings placeholders), `CryptoManager` (real, streaming AES-GCM), `VaultRepository` stub with final-shaped API + TODOs, icon placeholder, Actions debug-APK workflow | ✅ This delivery |
| **1 — MVP** | PIN create/verify (salted hash) + biometric, Photo Picker import pipeline + thumbnails, Room index, grid + viewer (FLAG_SECURE), folders, export via MediaStore, delete-originals consent flow, break-in log, auto-lock | Next |
| **2 — Hardening** | Chunked-AEAD format v2, instrumented crypto round-trip tests (Keystore needs device/emulator), viewer video seeking, edge cases (key invalidation, low storage, huge files) | |
| **3 — Store prep** | AdMob (outside lock/viewer only), final icon/artwork, listing text EN+BN, privacy policy page, release signing + AAB workflow, closed testing | |

## 8. Key decisions already made

- **PIN is a gate, not the encryption key.** Simpler, standard, and lets the
  PIN change without re-encrypting the whole vault. The Keystore key does
  the real protecting — including against someone browsing the file system.
- **Photo Picker first** — zero-permission import is the single biggest
  review-and-trust win available; broad media permission is only a fallback.
- **Trash before delete** — when we offer original-removal we prefer
  `createTrashRequest` (recoverable for 30 days) over permanent delete.
- **No disguise features, ever** — see §4.

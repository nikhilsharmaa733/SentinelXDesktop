# SentinelX Desktop

Companion desktop app to the Android vault at `~/AndroidStudioProjects/SentinelX`.

## What this is

A **dedicated desktop application**, not a port of the phone UI. Free to differ in every
respect but one: it must read Migration Seal (`.sxv`) archives the Android app produces.
That format is the only coupling between the two programs.

**The Android app is never modified by work in this repo.**

| | |
|---|---|
| Stack | Kotlin + Compose Desktop 1.7.3, Kotlin 2.0.21 |
| Platforms | Windows (target), Linux (development + use) |
| Unlock | Master password — no Keystore, no biometrics |
| Scope | Everything except camera scanning; images attach from disk |

## Environment

- **No system JDK.** `JAVA_HOME` in `~/.bashrc` and `~/.profile` points at Android Studio's
  bundled JBR 21 (`~/Downloads/android-studio-quail1-linux/android-studio/jbr`), and
  `~/.gradle/gradle.properties` sets `org.gradle.java.home` to the same. Do **not** add
  `jvmToolchain(...)` to the build — Gradle will hunt for a JDK that isn't installed and fail.
- `./gradlew run` launches the app natively on Linux in seconds. No emulator, no device.
- `./gradlew test` runs the format tests.

## The format contract — read before touching `core/format`

```
vault_data.json   encrypted payload (a base64 STRING, not JSON at this layer)
images/<name>     one entry per image, flat, no subdirectories
```

Payload envelope:
```
v2 (current):  [ "SXV2" | salt(16) | iv(12) | AES-256-GCM ct+tag ]  @ 600,000 PBKDF2 iters
v1 (legacy):   [          salt(16) | iv(12) | AES-256-GCM ct+tag ]  @  65,536  — read only, never write
```

⚠️ **Base64 trap.** Android encodes with `Base64.DEFAULT`: line-wrapped at 76 chars with `\n`.
`Base64.getDecoder()` **throws** on that — a valid vault looks corrupt. Use `getMimeDecoder()`.
Verified against a real 3 MB archive: 950 wrapped lines, LF endings, `SXV2` marker present.

⚠️ **Field names are the wire format.** `MasterBackup.kt` mirrors the six Room entities in
`SentinelX/app/src/main/java/com/nikhil/sentinelx/data/`. Renaming a property silently breaks
compatibility — the field arrives absent and is defaulted, losing data with no error. If a name
changes on Android, change it here in the same commit.

⚠️ **Two KDFs on purpose.** PBKDF2/600k for `.sxv` because the Android format fixes it;
Argon2id (Bouncy Castle, pure Java) for the local store because that format is ours. Do not
"improve" the `.sxv` KDF — it would produce archives the phone cannot open.

Separator collisions to keep in mind: `ChronicleEntity.pages` is `|`-separated and
`TransactionEntity.billImageUris` is comma-separated, while both characters are legal in
user-entered titles. Parsing lives in `MasterBackup.kt` helpers; don't re-split inline.

## Design constraints

- **Decrypted data never touches the disk.** Windows has no equivalent to Android's app-private
  storage, so extracting images to temp would be a real regression versus the phone. Images live
  in memory as `filename → ByteArray`.
- **No network code anywhere.** No HTTP client belongs on the dependency list — the offline
  guarantee is architectural, not a setting.
- Keep the Elden Ring identity (gold/cyan on near-black, serif display, rune glyphs) but lay out
  for a desktop: sidebar, side-by-side panes, keyboard shortcuts, right-click menus. Do not
  stretch phone screens across a monitor. Animation calmer than mobile — shimmer on every card is
  charming at 6 inches and tiring at 27.

## Where desktop is genuinely weaker — say so in the README

- No `FLAG_SECURE` equivalent; Windows cannot block screenshots of an ordinary app.
- No Keystore. The master password *is* the vault, with no recovery.
- Heap dumps of a running unlocked app expose secrets.

## Verifying against a real archive

Unit tests only prove this code is self-consistent — the same implementation writes and reads
them. Use a real phone export to prove the two apps agree:

```bash
read -s -p "Password: " SXV_PASSWORD && export SXV_PASSWORD && echo
./gradlew verifySxv --args="/path/to/vault.sxv" -q
unset SXV_PASSWORD
```

Password comes from the environment, never `--args` (visible in `ps`, saved in shell history).
Output is counts and integrity checks only — no field values — so it is safe to share.

## Status — feature complete

- ✅ `core/format` — `.sxv` read/write, both versions. **Verified against a real phone
  export**: counts matched, so the contract holds end to end.
- ✅ `core/store` — Argon2id local vault, atomic versioned saves, sealed image blobs
- ✅ All six panes, full CRUD, images attach from disk
- ✅ Command palette (Ctrl+K), password health, expiry dashboard, password generator
- ✅ Import and export `.sxv`, CSV export for the ledger
- ✅ Version history (undo), favourites
- ⬜ Windows installer — see `PACKAGING.md`. `jpackage` cannot cross-compile, and the
  JBR on this machine has no `jpackage` at all, so `createDistributable` fails here by
  design. `packageUberJarForCurrentOS` works.

42 tests passing.

## Things that will bite whoever works on this next

- **Sidecar, not schema.** Favourites and any future desktop-only state go in
  `Session.readSidecar`/`writeSidecar`, never in `MasterBackup`. That data class is
  the wire format; adding a field changes what the phone reads and Gson would drop it
  there silently anyway.
- **Uniqueness constraints the phone enforces but never surfaces.** `artifacts` is
  UNIQUE on `(label1, label2)`, `chronicles` and `prophecies` on title, `accounts` on
  name — all with `REPLACE`, so a collision *destroys* the other row on restore. Every
  editor checks these before saving. Do not remove those checks.
- **Editing a transaction must preserve its timestamp.** The phone's unique index on
  `ledger` includes it, so changing it creates a second row on restore rather than
  updating the existing one.
- **Undo is built on store snapshots**, not a recycle bin — one mechanism covers bad
  deletes, bad edits and bad imports alike.

Full plan: `~/.claude/plans/typed-pondering-aho.md`

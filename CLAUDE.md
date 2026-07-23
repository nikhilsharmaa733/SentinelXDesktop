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
| Platforms | Windows, Linux, macOS (Apple Silicon) — installers built in CI |
| Unlock | Master password — no Keystore, no biometrics |
| Scope | Everything except camera scanning; images attach from disk |

## Environment

- **System OpenJDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64`.** `JAVA_HOME` (`~/.bashrc`,
  `~/.profile`) and `~/.gradle/gradle.properties` (`org.gradle.java.home`) both point at it.
  It was installed specifically for `jpackage` — the Android Studio JBR that was here first
  has no `jpackage`, so it could build the app but not the installers. Do **not** add
  `jvmToolchain(...)` — pinning an exact toolchain makes Gradle hunt for a JDK and fail;
  letting it use the daemon JVM is what works.
- `./gradlew run` launches the app natively on Linux in seconds. No emulator, no device.
- `./gradlew test` runs the format tests.
- **`./run-isolated.sh` when someone is testing the app while you keep working.**
  `./gradlew run` puts `build/classes/` on the classpath and the JVM loads classes
  lazily as the user navigates, so rebuilding mid-session can make a screen they have
  not opened yet disappear — a real click on "Version History" once produced
  `ClassNotFoundException: HistoryDialogKt` for a class that compiled fine. The script
  runs a snapshot copy of the uber jar, which holds no reference to `build/`.

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

## Status — feature complete and shipping

- ✅ `core/format` — `.sxv` read/write, both versions. **Verified against a real phone
  export**: counts matched, so the contract holds end to end.
- ✅ `core/store` — Argon2id local vault, atomic versioned saves, sealed image blobs
- ✅ All six panes, full CRUD, images attach from disk
- ✅ Command palette (Ctrl+K), password health, expiry dashboard
- ✅ Password generator — in the login editor **and** standalone from the sidebar
- ✅ Ledger balance-trend graph — cumulative balance over time, hover crosshair + tooltip
  (`ui/panes/LedgerGraph.kt`). Note the Compose gotcha it fixed: a `fillMaxSize()` list
  after other content in a `Column` renders off-screen; the transaction list must be
  `weight(1f)`.
- ✅ Import and export `.sxv`, CSV export for the ledger
- ✅ Version history (undo), favourites
- ✅ **Cross-platform installers via CI** — Windows/Linux/macOS. See "Releasing" below.

42 tests passing.

## Releasing

Installers are built in GitHub Actions — `.github/workflows/release.yml`. jpackage cannot
cross-compile, so each OS builds on its own runner: **Windows** `.msi`/`.exe`, **Linux**
`.deb`/`.rpm`/portable `.tar.gz`, **macOS** arm64 `.dmg`. A final `release` job attaches
everything to one public GitHub Release. Repo: `nikhilsharmaa733/SentinelXDesktop`.

Cut a release by bumping `version` **and** `packageVersion` in `build.gradle.kts` (both;
leave `upgradeUuid` alone), commit, then push a `v*` tag — the tag is what triggers build
+ publish:

    git tag v1.0.5 && git push origin v1.0.5

`workflow_dispatch` runs a test build (artifacts only, no release). Current release: **v1.0.4**.

Locally (the system JDK has jpackage): `./gradlew packageDeb` / `packageRpm` /
`packageAppImage` build Linux artifacts; `runnableJar` builds a launchable uber jar.
Windows and macOS installers only come from CI.

**macOS Gatekeeper — not a bug.** The `.dmg` is ad-hoc signed by jpackage but NOT notarized
(no paid Apple Developer cert), so macOS 26 blocks it on first launch: the icon bounces once
and quits. Fix is a one-time recipient step (`xattr -dr com.apple.quarantine
/Applications/SentinelX.app`, or Settings → Privacy & Security → Open Anyway), documented in
the README and prepended to every release body. Only paid notarization removes the prompt.
The macOS CI leg is **arm64-only** on purpose — Intel (`macos-13`) runners queue
unpredictably and would stall the `release` job that waits on them.

Pushing needs the user's GitHub PAT (`repo` scope; add `workflow` scope if the push touches
`.github/workflows/**`). This Linux session has no stored credentials — the user runs `git push`.

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

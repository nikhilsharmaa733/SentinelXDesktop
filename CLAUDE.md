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

⚠️ **`VaultMerge.kt` is a mirrored file.** `SentinelX/app/src/main/java/com/nikhil/sentinelx/data/VaultMerge.kt`
is the same code, byte for byte, apart from its package line and the mirror comment — a
`diff` of the two is the check. The same merge run on the phone and on the desktop has to
produce the same vault, so a change to one is a change to both. Note especially that
record identity is **the unique index Room declares**, not the primary key: `id` is a
per-device autoincrement and means nothing across machines.

⚠️ **A full export must stay untagged.** `MasterBackup.sections` is null for a whole-vault
archive and names its contents only for a scoped one, so pre-v8 builds keep reading full
archives as they always did. The flip side is that a pre-v8 build reading a *scoped*
archive treats it as a full backup — which is why scoped exports are named
`Sentinel_<Section>_*.sxv`.

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
- ✅ All seven panes, full CRUD, images attach from disk
- ✅ Command palette (Ctrl+K), password health, expiry dashboard
- ✅ Password generator — in the login editor **and** standalone from the sidebar
- ✅ Ledger balance-trend graph — cumulative balance over time, hover crosshair + tooltip
  (`ui/panes/LedgerGraph.kt`). Note the Compose gotcha it fixed: a `fillMaxSize()` list
  after other content in a `Column` renders off-screen; the transaction list must be
  `weight(1f)`.
- ✅ **Cash Book** (`ui/panes/CashBookPane.kt`) — the daily cash handover: paired
  evening/morning day cards, the note-by-note `DenominationCounter`, reconciliation
  against the stated amount, carry-forward of last night's tally, note inventory,
  verification workflow, and CSV + printable-HTML export (`core/format/CashBookExport.kt`).
  Added `cashBook` to `MasterBackup` and took the archive to **v7**. Every movement on a
  day is a full tile of its own — the evening and morning slots lead the card and still
  prompt when empty, but a third or fourth entry is no longer demoted to a sub-row.
- ✅ **Merge import and scoped transfers** (`core/format/VaultMerge.kt`,
  `ui/components/TransferDialogs.kt`) — import offers Merge or Replace, and on a clash
  offers do-not-copy / replace / keep-both, with per-section counts shown *before* the
  choice. Every pane also carries its own IMPORT/EXPORT for just its records. Added
  `sections` to `MasterBackup` and took the archive to **v8**.
- ✅ **Floating editor panels** (`ui/Panels.kt`) — every add/edit form is a draggable,
  resizable, non-modal panel instead of a modal dialog. Several can be open at once,
  across sections, with the pane behind them still live. Resize is per-panel and
  deliberately not persisted; reopening restores the editor's own default size.
- ✅ **Login site suggestions** — the login editor offers the sites already in the vault
  as you type, matching the phone's `AddLoginScreen`.
- ✅ **Notes upgrade** (`ui/panes/NotesPane.kt`, `ui/panes/NoteEditor.kt`) — text
  **and checklist** notes, folders (chips built from the notes themselves, the `book`
  pattern), pin, archive, per-note colour, per-note lock (curtain + REVEAL here; a
  biometric gate on the phone), live search across title/body/steps/folder, sort by
  recent/title/sigil, tappable checkboxes in the reader. `ProphecyEntity` gained seven
  nullable-or-defaulted fields — the archive stayed **v8**, old builds simply drop them.
- ✅ Import and export `.sxv`, CSV export for the ledger
- ✅ Version history (undo), favourites
- ✅ **Cross-platform installers via CI** — Windows/Linux/macOS. See "Releasing" below.

115 tests passing.

## Releasing

Installers are built in GitHub Actions — `.github/workflows/release.yml`. jpackage cannot
cross-compile, so each OS builds on its own runner: **Windows** `.msi`/`.exe`, **Linux**
`.deb`/`.rpm`/portable `.tar.gz`, **macOS** arm64 `.dmg`. A final `release` job attaches
everything to one public GitHub Release. Repo: `nikhilsharmaa733/SentinelXDesktop`.

Cut a release by bumping `version` **and** `packageVersion` in `build.gradle.kts` (both;
leave `upgradeUuid` alone), commit, then push a `v*` tag — the tag is what triggers build
+ publish:

    git tag v1.0.5 && git push origin v1.0.5

`workflow_dispatch` runs a test build (artifacts only, no release). Current release: **v1.1.0**
(Cash Book).

**A push is not a release.** Pushing `main` only moves the code; the CI is triggered by the
**tag**, and nothing is built or published without one. If a "release" appears to have gone
out but no installers exist, check `git ls-remote --tags origin` first — a missing tag is the
usual answer.

**Bump both version fields.** `version` names the jar and the release artefacts;
`packageVersion` is what jpackage stamps *inside* the installers, and on Windows it is what
the MSI upgrade check compares — leaving it behind makes the new installer read as a reinstall
of the old version rather than an upgrade. They have already drifted apart once (v1.1.0).

Locally (the system JDK has jpackage): `./gradlew packageDeb` / `packageRpm` /
`packageAppImage` build Linux artifacts into `build/compose/binaries/main/deb/` etc.;
`runnableJar` builds a launchable uber jar. Windows and macOS installers only come from CI.

Installing the local `.deb` on Zorin (Ubuntu-based):

    sudo apt install ./build/compose/binaries/main/deb/sentinelx_<version>_amd64.deb

It installs to `/opt/sentinelx` and adds a desktop entry, so it launches from the app menu.
Upgrading in place is the same command with the newer file. The vault lives in
`$XDG_DATA_HOME/SentinelX` (typically `~/.local/share/SentinelX`) — **outside** the package,
so uninstalling or reinstalling never touches it. Wiping that directory is the only way to
reset a forgotten master password, and it destroys the vault with it.

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

- **`GemCard` used to eat any bounded parent.** Its accent stripe was
  `Box(Modifier.width(3.dp).fillMaxHeight())`, and a `Box` sizes to its largest child —
  so the card grew to whatever height it was *offered*. Inside a `LazyColumn` item the
  offered height is effectively infinite and collapses back to the content, which is why
  every caller looked fine for months. The first time one was placed in a plain `Column`
  (the Cash Book's pinned note inventory) it swallowed the pane and pushed the search box
  and the entire day list off the bottom. The stripe is now wrapped in
  `Box(Modifier.matchParentSize())`, which is measured after the content and does not feed
  into the parent's size. Same family as the `weight(1f)` rule below: **any
  `fillMax*`/`fillMaxSize` inside a component you intend to reuse is a constraint bug
  waiting for a bounded parent.**

- **Panels are non-modal, and that is load-bearing.** The pane behind an open editor
  keeps taking clicks and scrolls, so a panel must own a `pointerInput` node over its
  whole area or clicks fall straight through it — a `background` is a draw modifier and
  stops nothing. `PanelHost`'s raise-on-press handler doubles as that node. Equally, the
  host `Box` must stay free of backgrounds and pointer modifiers, or it would swallow
  every click aimed at the app underneath.

- **Never let two panels edit one record.** Each holds the snapshot it opened with, so
  the second to save silently undoes the first. `PanelRequest.identity` prevents it by
  raising the panel that already has the record. New records have no identity and are
  free to multiply. Anything that replaces the whole vault — import, version restore,
  lock — calls `panels.closeAll()` for the same reason.

- **A `pointerInput` key change cancels a drag in flight.** The resize grip was keyed on
  the panel's measured content height; resizing changed the height, the height changed
  the key, Compose rebuilt the gesture detector, and the drag died about a centimetre
  in. Gesture modifiers on anything they themselves resize must be keyed on `Unit` and
  read changing values through `rememberUpdatedState`.

- **A parent's `onSizeChanged` fires after its children are measured.** `PanelHost` first
  used `fillMaxSize()` + `onSizeChanged` to learn its own size, so the first panel asked
  where to centre itself while the host still measured zero, gave up, and — since its own
  size never changed again — was never asked twice. It rendered at alpha 0 forever.
  `BoxWithConstraints` knows the size before the content composes; use it when a child's
  placement depends on the parent's size.

- **Cascading panels must share one anchor.** Centring each panel on its own width moved
  a 720-wide editor further left than the 48px cascade moved it right, so it landed
  exactly on top of a 560-wide one and hid it completely. `initialOffset` centres on a
  nominal width so the step is the only thing that separates them.

- **Sidecar, not schema.** Favourites and any future desktop-only state go in
  `Session.readSidecar`/`writeSidecar`, never in `MasterBackup`. That data class is
  the wire format; adding a field changes what the phone reads and Gson would drop it
  there silently anyway. The one legitimate exception so far is `cashBook`, which is
  *meant* to travel — and it landed on the phone in the same piece of work, which is the
  bar for adding anything else to that class.
- **Uniqueness constraints the phone enforces but never surfaces.** `artifacts` is
  UNIQUE on `(label1, label2)`, `chronicles` and `prophecies` on title, `accounts` on
  name — all with `REPLACE`, so a collision *destroys* the other row on restore. Every
  editor checks these before saving. Do not remove those checks.
- **Editing a transaction must preserve its timestamp.** The phone's unique index on
  `ledger` includes it, so changing it creates a second row on restore rather than
  updating the existing one.
- **Undo is built on store snapshots**, not a recycle bin — one mechanism covers bad
  deletes, bad edits and bad imports alike.
- **Cash entries: `amount` is authoritative, `denominations` is commentary.** Every total
  and export reads `amount` and never parses the JSON breakdown, so a corrupt breakdown
  costs the note detail and nothing else. `decodeDenominations` must never throw.
- **Notes: `checkItems` is authoritative, `content` is its plain-text mirror.** Every
  save and every checkbox tick regenerates `content` via `itemsToText()` — it is what
  search, copy and pre-v8 builds read. `decodeCheckItems` never throws (empty list on
  garbage). Never write one field without the other; `AppState.setNoteChecklist` is the
  one place that does it right.
- **Note toggles go through `patchProphecy`, not `upsertProphecy`.** Upsert stamps
  `timestamp = now`; a pin, archive flip or checkbox tick must not shove the note to the
  top of "recent". The phone draws the same line with `@Update`.
- **Merge fingerprints for notes call `noteType()`, never the raw `type` field.** Gson
  fills an absent `type` with `"TEXT"` here (all-defaults data classes get a real no-arg
  constructor) but with null on Android — the normalised accessor is what keeps the same
  archive classifying identically on both apps. Tested in both repos' `VaultMergeTest`.
- **Locked notes must never leak their body** — not in the list row, not in the command
  palette subtitle, not in the reader until REVEAL. The palette is the easy one to
  forget: it echoes subtitles straight onto the screen.
- **`noteColorChoices` is mirrored hex-for-hex with the phone** (`SentinelComponents.kt`
  there, `NotesPane.kt` here). The stored value is the hex string, so a foreign colour
  still renders — but keep the palettes identical or the same note offers different
  swatches on each app.
- **`entryDate` is UTC midnight, not local midnight.** A cash book's date is a calendar
  day, not an instant. Use `businessDateOf()`; formatting it in the local zone shows the
  previous day west of Greenwich.
- **The cash book exports are plaintext**, like the ledger CSV and for the same reason.
  Both dialogs warn every time. Do not remove the warning, and do not extend plaintext
  export to logins or card secrets.

Full plan: `~/.claude/plans/typed-pondering-aho.md`

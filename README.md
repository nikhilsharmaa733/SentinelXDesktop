# SentinelX Desktop

An offline personal vault for Windows and Linux — passwords, ID/bank cards, scanned
documents, notes, and a multi-account ledger. Nothing leaves the machine: there is no
network layer and no server.

Companion to the SentinelX Android app. The two share exactly one thing — the **Migration
Seal (`.sxv`)** archive format — so a vault exported on the phone opens on the desktop and
back again.

## Highlights

- **Multi-pane desktop layout** — list and detail side by side, not a phone drill-down.
- **Command palette + global search** (`Ctrl+K`) across every item at once.
- **Password health audit** — weak, reused, and stale passwords across all logins.
- **Ledger with a balance-trend graph** — cumulative balance over time, hover for the exact
  figure on any day; plus category breakdown and CSV export.
- **Expiry dashboard** for passports, licences, and cards.
- **Password generator** built into the login editor.

## Security

- Local vault is encrypted at rest with **Argon2id** + AES-256-GCM; decrypted data never
  touches the disk.
- `.sxv` import/export matches the phone's envelope: **PBKDF2-HMAC-SHA256 (600k)** + AES-256-GCM.
- No network code anywhere — the offline guarantee is architectural, not a setting.

Desktop is honestly weaker than the phone in three ways, stated plainly: no `FLAG_SECURE`
(screenshots are possible), no hardware keystore (the master password *is* the vault, no
recovery), and heap memory is not protected. Auto-lock and a secure 30-second clipboard
mitigate, but do not eliminate, these.

## Build & run

```bash
./gradlew run            # launch natively (Linux/macOS/Windows)
./gradlew test           # format round-trip + unit tests
./gradlew packageDeb     # Linux installer  → build/compose/binaries/main/deb/
```

**Installers for every platform** are built in CI — see `.github/workflows/release.yml`.
`jpackage` cannot cross-compile, so each OS's packages are produced on its own runner
(Windows `.msi`/`.exe`, Linux `.deb`/`.rpm`, macOS arm64 `.dmg`) and published to the
[Releases](../../releases) page on every `v*` tag. Details in [`PACKAGING.md`](PACKAGING.md).

## Installing on macOS — first launch

The macOS `.dmg` (Apple Silicon / arm64) is **not signed with an Apple Developer ID and
not notarized** — there's no paid Apple Developer certificate behind this project. The app
*is* ad-hoc signed (jpackage does this automatically), and the build is genuine, but macOS
Gatekeeper still blocks apps it can't trace to a notarized developer. On first launch you'll
see the icon bounce once and quit, with no error. This is expected for any unsigned app;
you clear it once and never see it again.

Drag **SentinelX** into `/Applications`, then do **one** of the following:

**Terminal (most reliable):**

```bash
xattr -dr com.apple.quarantine /Applications/SentinelX.app
open /Applications/SentinelX.app
```

**Or via the GUI** (macOS 13–15 differ; on macOS 15 Sequoia and macOS 26 Tahoe the old
right-click → Open shortcut is gone): double-click once, let it get blocked, then go to
**System Settings → Privacy & Security**, scroll to *"SentinelX was blocked…"*, and click
**Open Anyway**.

> **Still won't open** (e.g. you removed quarantine but it now refuses to even bounce)?
> macOS has cached a "denied" verdict. Reset it by clearing every attribute and re-applying
> a fresh ad-hoc signature, which changes the code hash and invalidates the stale cache:
>
> ```bash
> xattr -cr /Applications/SentinelX.app
> codesign --force --deep --sign - /Applications/SentinelX.app
> open /Applications/SentinelX.app
> ```

An arm64 `.dmg` **cannot** run on an Intel Mac. If you're on Intel (`uname -m` prints
`x86_64`), this build won't work regardless of the steps above — it needs a separate x64 build.

## Stack

Kotlin · Compose Desktop · Gson · Bouncy Castle (Argon2id). Pure JVM, no native
dependencies beyond Skia.

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

**Windows installers** are built in CI — see `.github/workflows/windows.yml`. `jpackage`
cannot cross-compile, so the `.msi`/`.exe` are produced on a Windows runner and downloaded
from the run's **Artifacts**. Details in [`PACKAGING.md`](PACKAGING.md).

## Stack

Kotlin · Compose Desktop · Gson · Bouncy Castle (Argon2id). Pure JVM, no native
dependencies beyond Skia.

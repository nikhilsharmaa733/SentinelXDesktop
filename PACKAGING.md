# Shipping SentinelX

Turning the project into something you can hand to someone on Windows or Linux.

---

## The one blocker on this machine

Installers are built by **`jpackage`**, which ships with a full JDK. The Java in use
here is the **JetBrains Runtime bundled with Android Studio** — a *runtime*, not a
JDK. It has no `jpackage` at all, so:

```
./gradlew createDistributable
→ Failed to check JDK distribution: 'jpackage' is missing
```

That is not a fault in the build. Nothing can be packaged until a real JDK exists.

### Fix it once

```bash
sudo apt install openjdk-21-jdk
```

Then point Gradle at it instead of the JBR — edit `~/.gradle/gradle.properties`:

```properties
org.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64
```

Verify:

```bash
/usr/lib/jvm/java-21-openjdk-amd64/bin/jpackage --version
```

The Android project keeps building fine on this JDK; it never needed the JBR
specifically, that was only what happened to be installed.

> `jpackage` **cannot cross-compile.** It builds installers only for the OS it runs
> on. A JDK on Linux gets you Linux packages, never a Windows `.msi`.

---

## Linux — from this machine

```bash
./gradlew packageDeb        # → build/compose/binaries/main/deb/sentinelx_1.0.0_amd64.deb
./gradlew packageAppImage   # → a self-contained directory, no install needed
```

The `.deb` installs to `/opt/sentinelx`, adds a menu entry under Utility, and bundles
its own Java runtime — the recipient needs nothing preinstalled.

```bash
sudo dpkg -i build/compose/binaries/main/deb/sentinelx_1.0.0_amd64.deb
```

AppImage output is a plain folder you can zip and hand over; it runs from anywhere,
including a USB stick. Good for a vault you want to carry rather than install.

---

## Windows — three routes

### 1. Conveyor — cross-build from Linux (recommended)

[Hydraulic Conveyor](https://conveyor.hydraulic.dev) is the only practical way to
produce a Windows `.msi` **without touching a Windows machine**. It also handles
signing and update feeds. Free for open-source projects, paid otherwise.

```bash
conveyor make windows-zip     # unsigned, works immediately
conveyor make windows-msi     # installer
```

### 2. On a Windows machine

Install a full JDK 17+ (Temurin, Liberica, or Oracle — **not** a JRE, **not** the
JetBrains Runtime), copy the project across, then:

```bat
gradlew.bat packageMsi
```

Output: `build\compose\binaries\main\msi\SentinelX-1.0.0.msi`. Real installer, Start
Menu entry, desktop shortcut, bundled runtime, and a directory chooser.

### 3. Plain JAR — no installer

```bash
./gradlew runnableJar     # NOT packageUberJarForCurrentOS
```

> ⚠️ **`packageUberJarForCurrentOS` alone produces a jar that will not start.**
> Bouncy Castle ships a *signed* jar, and merging it keeps its signature entries
> (the `.SF` and `.RSA` files under META-INF) whose digests describe the original
> archive rather than the merged one. The JVM refuses to load it:
>
> ```
> SecurityException: Invalid signature file digest for Manifest main attributes
> ```
>
> `runnableJar` strips those entries and writes to `build/dist/`. Verified: it
> launches. Always ship the `build/dist/` jar, never the `build/compose/jars/` one.

Ship the jar with a launcher:

```bat
@echo off
start "" javaw -jar "%~dp0SentinelX-windows-x64-1.0.0.jar"
```

Requires Java 17+ on the target machine.

> ⚠️ **The uber jar is platform-specific despite bundling everything.** It contains
> Skia's native libraries for the platform it was built on, so a jar built on Linux
> will not run on Windows. Build it on the target OS, or use routes 1–2.

---

## Icons

`icons/sentinelx.ico` and `icons/sentinelx.png` are generated from the Android app's
launcher icon, and `src/main/resources/app-icon.png` is the in-window icon so
`./gradlew run` does not show the generic Java coffee cup.

The `.ico` embeds 16–256px. Windows picks the closest size for the taskbar, Start
Menu and Alt-Tab independently, so a single-size icon looks pixelated in whichever
context does not match. To regenerate after changing the artwork:

```bash
convert <source>.png -define icon:auto-resize=256,128,64,48,32,16 icons/sentinelx.ico
convert <source>.png -resize 512x512 icons/sentinelx.png
convert <source>.png -resize 256x256 src/main/resources/app-icon.png
```

---

## Runtime trimming

`includeAllModules = false` with an explicit module list roughly halves installer
size against the default of bundling every JDK module.

Three entries look unnecessary and are not:

| Module | Why |
|---|---|
| `java.naming` | Bouncy Castle provider registration |
| `java.security.jgss` | Same |
| `jdk.unsupported` | `sun.misc.Unsafe`, used by the Skia bindings |

Dropping any of these fails **at runtime, not at build time** — the installer builds
cleanly and the app dies on launch. If you trim further, always launch the *packaged*
build and unlock a vault before shipping it.

---

## Signing, and what recipients will see

Unsigned Windows binaries trigger SmartScreen: *"Windows protected your PC"*, with
"Run anyway" hidden behind **More info**. This happens regardless of how the
installer was built. Suppressing it needs a code-signing certificate from a
commercial CA — an annual cost, hard to justify for a personal vault.

Linux has no equivalent gate; a `.deb` installs without complaint.

---

## Before you hand it to anyone

The `upgradeUuid` in `build.gradle.kts` is fixed deliberately. Without it, jpackage
generates a new upgrade code per build and Windows treats each version as a separate
product — installing 1.0.1 would leave 1.0.0 in place instead of upgrading it. Bump
`packageVersion` for each release, never the UUID.

And a caveat specific to this app: **a fresh install starts with an empty vault.**
There is no shared storage and no sync. Whoever runs it creates their own master
password and imports their own `.sxv`. That is the intended design — the vault is
offline and device-bound — but it is worth saying out loud if you are sharing the
app rather than your data.

# Packaging

## The constraint

`jpackage` **cannot cross-compile**. It builds installers only for the OS it runs
on, so a Windows `.msi` cannot be produced from Linux.

There is a second constraint specific to this machine: the JDK in use is the
**JetBrains Runtime bundled with Android Studio**, which is a *runtime*, not a full
JDK — it ships no `jpackage` at all. `./gradlew createDistributable` therefore fails
with:

```
Failed to check JDK distribution: 'jpackage' is missing
```

That is expected here, and not a fault in the build.

---

## What works right now

```bash
./gradlew packageUberJarForCurrentOS
```

Produces `build/compose/jars/SentinelX-<os>-<arch>-1.0.0.jar` (~82 MB — Skia's native
libraries dominate). Runs anywhere with **Java 17+** installed:

```bash
java -jar SentinelX-linux-x64-1.0.0.jar
```

The uber JAR is platform-specific despite the name: it bundles Skia natives for the
platform it was built on. A JAR built on Linux will not run on Windows. Build it on
each target, or use one of the options below.

---

## Getting a Windows installer

Three routes, cheapest first.

### 1. JAR + launcher, no installer

Build the uber JAR on a Windows machine and ship it with a `.bat`:

```bat
@echo off
start "" javaw -jar "%~dp0SentinelX-windows-x64-1.0.0.jar"
```

Requires Java 17+ on the target machine. Zero build tooling, no signing, works
immediately. Good enough for personal use on your own PC.

### 2. jpackage on Windows

Install a **full JDK 17+** (Temurin, Liberica, or Oracle — *not* a JRE and not the
JetBrains Runtime), clone the repo there, and run:

```bat
gradlew.bat packageMsi
```

Output lands in `build\compose\binaries\main\msi\`. This produces a real installer
with a Start Menu entry, a desktop shortcut, and a bundled Java runtime, so the user
needs nothing preinstalled.

The `upgradeUuid` in `build.gradle.kts` is fixed deliberately. Without it, jpackage
generates a fresh upgrade code per build and Windows treats every version as a
separate product — installing 1.0.1 would leave 1.0.0 in place rather than upgrading
it.

### 3. Conveyor — cross-build from Linux

[Hydraulic Conveyor](https://conveyor.hydraulic.dev) builds Windows and macOS
packages from any host, which is the only way to produce a `.msi` without touching a
Windows machine. Free for open-source projects, paid otherwise.

---

## Runtime trimming

`includeAllModules = false` with an explicit module list keeps the bundled runtime
small; the default bundles every JDK module and roughly doubles installer size.

Two entries look unnecessary and are not:

| Module | Why |
|---|---|
| `java.naming` | Bouncy Castle's provider registration |
| `java.security.jgss` | Same |
| `jdk.unsupported` | `sun.misc.Unsafe`, used by the Skia bindings |

Dropping these fails **at runtime, not at build time** — the installer builds fine
and the app dies on launch, which is the worst way to discover it. If you trim
further, launch the packaged build and unlock a vault before shipping it.

---

## Signing

Unsigned Windows binaries trigger a SmartScreen warning ("Windows protected your
PC"), regardless of how they were built. Suppressing it needs a code-signing
certificate from a commercial CA — an annual cost, and not worth it for a personal
vault you install on your own machines.

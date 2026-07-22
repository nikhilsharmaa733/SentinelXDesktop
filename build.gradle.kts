import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.nikhil.sentinelx.desktop"
version = "1.0.0"

// No jvmToolchain() here on purpose. Requesting a specific toolchain makes Gradle
// hunt for that exact JDK and fail if it is absent — this machine has only the
// JetBrains Runtime 21 bundled with Android Studio, no system JDK. Letting the
// build use the daemon JVM keeps it working with what is actually installed.
// jpackage needs 17+, so 21 is fine.

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Same Gson the Android app serialises MasterBackup with. The archive payload
    // is matched field-for-field, so the JSON library must behave identically.
    implementation("com.google.code.gson:gson:2.10.1")

    // Argon2id for the LOCAL vault key. Bouncy Castle is pure Java — deliberately
    // chosen over argon2-jvm, which ships native binaries that would complicate
    // packaging for Windows. The .sxv format keeps PBKDF2 (see SxvCrypto).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()

    // Gradle prints nothing per-test by default, so a green BUILD SUCCESSFUL looks
    // identical whether 19 tests passed or none ran at all. Print each one.
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }

    // Per-class summary at the end, so the counts are visible without opening the report.
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        if (desc.parent == null) {
            println(
                "\n  ${result.testCount} tests  ·  " +
                    "${result.successfulTestCount} passed  ·  " +
                    "${result.failedTestCount} failed  ·  " +
                    "${result.skippedTestCount} skipped"
            )
            println("  HTML report: file://${layout.buildDirectory.get()}/reports/tests/test/index.html\n")
        }
    }))
}

/**
 * Verifies a real Migration Seal exported from the phone.
 *
 *   read -s -p "Password: " SXV_PASSWORD && export SXV_PASSWORD && echo
 *   ./gradlew verifySxv --args="/path/to/vault.sxv" -q
 *   unset SXV_PASSWORD
 *
 * The password comes from the environment, never --args, because arguments are
 * visible in `ps` and recorded in shell history.
 */
tasks.register<JavaExec>("verifySxv") {
    group = "verification"
    description = "Parse a real .sxv archive and report counts (password via \$SXV_PASSWORD)"
    mainClass.set("com.nikhil.sentinelx.desktop.tools.VerifyArchiveKt")
    classpath = sourceSets["main"].runtimeClasspath
    environment("SXV_PASSWORD", System.getenv("SXV_PASSWORD") ?: "")
}

compose.desktop {
    application {
        mainClass = "com.nikhil.sentinelx.desktop.MainKt"

        nativeDistributions {
            // Msi is produced only when jpackage runs ON Windows; Deb only on Linux.
            // jpackage cannot cross-compile, so each host builds the one it can.
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "SentinelX"
            packageVersion = "1.0.0"
            description = "Offline personal vault"
            vendor = "Nikhil"
            copyright = "© 2026 Nikhil. All rights reserved."

            // Trim the bundled runtime to what is actually used. The default bundles
            // every JDK module and roughly doubles the installer size.
            //
            // java.naming and java.security.jgss look unrelated but are pulled in by
            // Bouncy Castle's provider registration; dropping them fails at runtime,
            // not at build time, which is the worst way to find out.
            includeAllModules = false
            modules(
                "java.base",
                "java.desktop",     // AWT: file dialogs, clipboard
                "java.logging",
                "java.naming",      // Bouncy Castle provider lookup
                "java.security.jgss",
                "java.instrument",
                "jdk.unsupported"   // sun.misc.Unsafe, used by Skia bindings
            )

            windows {
                menu = true
                shortcut = true
                dirChooser = true
                // Fixed UUID: without it, jpackage generates a new upgrade code on
                // every build and Windows treats each version as a separate product,
                // leaving old installs behind rather than upgrading them.
                upgradeUuid = "6E7A1C42-9B3D-4F81-A0C5-2D8E7F41B903"
            }

            linux {
                packageName = "sentinelx"
                menuGroup = "Utility"
            }
        }
    }
}

package com.nikhil.sentinelx.desktop.core.bridge

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Installs the browser side of the bridge: the native-messaging host script and,
 * for each browser found on the machine, the tiny manifest that authorises this
 * one extension to launch it. Also lays down an unpacked copy of the extension
 * for the user to load.
 *
 * Everything it writes is user-scoped and local; it grants no capability beyond
 * "this specific extension may speak to this specific host", pinned by the
 * extension's public-key identity so no other add-on can impersonate it.
 */
object BridgeInstaller {

    const val HOST_NAME = "com.nikhil.sentinelx.bridge"
    /** Deterministic Chromium id derived from the pinned `key` in manifest.json. */
    const val CHROME_EXT_ID = "lgijklkbehpbmjappnbkoipafgbbbbia"
    const val FIREFOX_EXT_ID = "sentinelx-bridge@nikhil.local"

    private val EXTENSION_FILES = listOf(
        "manifest.json", "background.js", "content.js", "popup.html", "popup.js",
        "icons/icon48.png", "icons/icon128.png"
    )

    data class Target(val label: String, val dir: File, val chromium: Boolean)
    data class Report(val hostPath: File, val extensionDir: File, val installed: List<String>, val skipped: List<String>)

    private val home get() = File(System.getProperty("user.home"))
    private val os get() = System.getProperty("os.name").lowercase()

    /** Where the host script and unpacked extension live once installed. */
    fun bridgeDir(): File = File(dataDir(), "bridge")
    fun extensionDir(): File = File(dataDir(), "extension")

    private fun dataDir(): File = when {
        os.contains("win") -> File(System.getenv("APPDATA") ?: "${home}\\AppData\\Roaming", "SentinelX")
        os.contains("mac") -> File(home, "Library/Application Support/SentinelX")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "${home}/.local/share", "SentinelX")
    }

    /**
     * The browsers whose config dirs exist right now. Chromium-family manifests
     * key on the extension's origin; Firefox keys on the add-on id.
     */
    fun detectedBrowsers(): List<Target> {
        val out = mutableListOf<Target>()
        fun chromium(label: String, vararg rel: String) {
            val dir = File(home, rel.joinToString(File.separator))
            if (dir.isDirectory) out += Target(label, File(dir, nativeHostSubdir()), true)
        }
        fun firefox(label: String, dir: File) {
            if (dir.isDirectory) out += Target(label, File(dir, "native-messaging-hosts"), false)
        }

        if (os.contains("mac")) {
            val app = "Library/Application Support"
            chromium("Chrome", app, "Google/Chrome")
            chromium("Brave", app, "BraveSoftware/Brave-Browser")
            chromium("Edge", app, "Microsoft Edge")
            chromium("Chromium", app, "Chromium")
            chromium("Vivaldi", app, "Vivaldi")
            firefox("Firefox", File(home, "$app/Mozilla"))
        } else if (os.contains("win")) {
            // Windows keys native hosts through the registry, not files — handled
            // by a note in the installer UI rather than here.
        } else {
            val cfg = ".config"
            chromium("Chrome", cfg, "google-chrome")
            chromium("Chromium", cfg, "chromium")
            chromium("Brave", cfg, "BraveSoftware/Brave-Browser")
            chromium("Edge", cfg, "microsoft-edge")
            chromium("Vivaldi", cfg, "vivaldi")
            chromium("Opera", cfg, "opera")
            firefox("Firefox", File(home, ".mozilla"))
            // Snap and Flatpak Firefox keep a private HOME.
            firefox("Firefox (snap)", File(home, "snap/firefox/common/.mozilla"))
            firefox("Firefox (flatpak)", File(home, ".var/app/org.mozilla.firefox/.mozilla"))
        }
        return out
    }

    private fun nativeHostSubdir(): String =
        if (os.contains("mac")) "NativeMessagingHosts" else "NativeMessagingHosts"

    /** Extracts the host script and extension, then writes a manifest per browser. */
    fun install(): Report {
        val host = writeHost()
        val ext = writeExtension()
        val installed = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        detectedBrowsers().forEach { target ->
            runCatching {
                target.dir.mkdirs()
                val manifest = manifestFor(host, target.chromium)
                File(target.dir, "$HOST_NAME.json")
                    .writeText(GsonBuilder().setPrettyPrinting().create().toJson(manifest))
                installed += target.label
            }.onFailure { skipped += "${target.label} (${it.javaClass.simpleName})" }
        }
        return Report(host, ext, installed, skipped)
    }

    /** Removes the per-browser manifests; leaves the extracted files in place. */
    fun uninstall() {
        detectedBrowsers().forEach { target ->
            runCatching { File(target.dir, "$HOST_NAME.json").delete() }
        }
    }

    fun isInstalled(): Boolean =
        detectedBrowsers().any { File(it.dir, "$HOST_NAME.json").isFile }

    // ── extraction ──────────────────────────────────────────────────────────

    private fun writeHost(): File {
        val dir = bridgeDir().apply { mkdirs() }
        val host = File(dir, "sentinelx_host.py")
        copyResource("/bridge/sentinelx_host.py", host)
        runCatching {
            Files.setPosixFilePermissions(host.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
        return host
    }

    private fun writeExtension(): File {
        val dir = extensionDir().apply { mkdirs() }
        EXTENSION_FILES.forEach { rel ->
            val dest = File(dir, rel).apply { parentFile?.mkdirs() }
            copyResource("/bridge/extension/$rel", dest)
        }
        return dir
    }

    private fun copyResource(resourcePath: String, dest: File) {
        val stream = BridgeInstaller::class.java.getResourceAsStream(resourcePath)
            ?: error("bundled resource missing: $resourcePath")
        stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    private fun manifestFor(host: File, chromium: Boolean): JsonObject = JsonObject().apply {
        addProperty("name", HOST_NAME)
        addProperty("description", "SentinelX Bridge native host")
        addProperty("path", host.absolutePath)
        addProperty("type", "stdio")
        if (chromium) {
            add("allowed_origins", GsonBuilder().create().toJsonTree(
                listOf("chrome-extension://$CHROME_EXT_ID/")
            ))
        } else {
            add("allowed_extensions", GsonBuilder().create().toJsonTree(listOf(FIREFOX_EXT_ID)))
        }
    }
}

package com.nikhil.sentinelx.desktop.core.bridge

/**
 * Site ↔ requester matching for the browser bridge.
 *
 * The logic is byte-identical to the Android app's `autofill/AutofillMatcher.kt`
 * apart from this package line and the mirror note — the same login must match
 * the same site whether the fill happens on the phone or in the desktop's
 * browser extension. If you change a heuristic here, change it there in the same
 * pair of commits; a `diff` of the two bodies is the check.
 *
 * On the desktop only the web-domain path is ever exercised (a browser is always
 * the requester), but the package path is kept so the two files stay identical.
 */
object BridgeMatcher {

    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "ac", "edu", "or", "ne", "go")

    private val GENERIC_SEGMENTS = setOf(
        "com", "org", "net", "io", "www", "app", "apps", "android", "mobile",
        "client", "beta", "free", "pro", "plus", "google", "gms", "android2",
        "main", "lite", "intl", "native", "browser"
    )

    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "com.google.android.apps.chrome",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
        "com.brave.browser", "com.microsoft.emmx",
        "com.opera.browser", "com.opera.mini.native", "com.opera.gx",
        "com.duckduckgo.mobile.android", "com.vivaldi.browser", "com.kiwibrowser.browser",
        "com.sec.android.app.sbrowser", "com.android.browser", "com.UCMobile.intl"
    )

    private val ALIASES = mapOf(
        "gmail" to "google",
        "googlemail" to "google",
        "youtube" to "google",
        "ymail" to "yahoo"
    )

    fun isBrowser(packageName: String): Boolean = packageName in BROWSER_PACKAGES

    fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    fun domainCore(host: String): String {
        val labels = host.lowercase()
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore(':')
            .removePrefix("www.")
            .split('.')
            .filter { it.isNotBlank() }
        if (labels.isEmpty()) return ""
        if (labels.size == 1) return labels[0]
        val idx = if (labels.size >= 3 && labels[labels.size - 2] in SECOND_LEVEL) labels.size - 3
        else labels.size - 2
        return labels[idx.coerceAtLeast(0)]
    }

    fun packageCandidates(packageName: String): List<String> =
        packageName.lowercase().split('.').filter { it.length >= 3 && it !in GENERIC_SEGMENTS }

    fun matches(siteName: String, webDomain: String?, packageName: String): Boolean {
        val raw = normalize(siteName)
        if (raw.length < 2) return false
        val site = ALIASES[raw] ?: raw

        if (!webDomain.isNullOrBlank()) {
            val core = normalize(domainCore(webDomain))
            if (core.isEmpty()) return false
            if (site == core || raw == core) return true
            if (site.length < 3) return false
            return core.contains(site) || site.contains(core) || normalize(webDomain).contains(raw)
        }

        if (isBrowser(packageName)) return false
        return packageCandidates(packageName).any { segment ->
            val s = normalize(segment)
            s == site || s == raw || (site.length >= 3 && (s.contains(site) || site.contains(s)))
        }
    }

    fun suggestedSiteName(webDomain: String?, packageName: String, appLabel: String?): String {
        if (!webDomain.isNullOrBlank()) {
            val core = domainCore(webDomain)
            if (core.isNotBlank()) return core.replaceFirstChar { it.uppercase() }
        }
        if (!appLabel.isNullOrBlank()) return appLabel
        return packageCandidates(packageName).lastOrNull()?.replaceFirstChar { it.uppercase() }
            ?: packageName
    }
}

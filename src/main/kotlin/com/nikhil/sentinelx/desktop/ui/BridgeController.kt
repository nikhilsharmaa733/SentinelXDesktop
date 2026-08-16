package com.nikhil.sentinelx.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nikhil.sentinelx.desktop.core.bridge.BridgeHandler
import com.nikhil.sentinelx.desktop.core.bridge.BridgeMatcher
import com.nikhil.sentinelx.desktop.core.bridge.BridgeProtocol
import com.nikhil.sentinelx.desktop.core.bridge.BridgeServer
import com.nikhil.sentinelx.desktop.core.format.LoginEntity
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A fill awaiting the user's approval in a dialog. Completing [decision] releases the worker. */
class BridgeFillRequest(
    val login: LoginEntity,
    val domain: String,
    private val decision: CompletableFuture<Boolean>
) {
    fun approve() = decision.complete(true)
    fun deny() = decision.complete(false)
}

/**
 * A captured credential awaiting confirmation. [siteName] is editable before
 * sealing. **The vault write happens inside [confirm], on the thread that
 * calls it** — the dialog's UI thread — never on the socket worker; the worker
 * only learns the outcome through the future. That is what keeps every vault
 * mutation on one thread, and what makes the browser's `saved` ack truthful:
 * it is sent only after the write actually happened.
 */
class BridgeCaptureRequest(
    val suggestedSite: String,
    val username: String,
    val password: String,
    val domain: String,
    val duplicateOf: LoginEntity?,
    /** Performs the write; returns false when the vault refused (e.g. locked). */
    private val write: (site: String) -> Boolean,
    private val decision: CompletableFuture<String?>
) {
    /** Seal under [site]. Runs the write on the calling (UI) thread. */
    fun confirm(site: String) {
        val trimmed = site.trim()
        val ok = trimmed.isNotEmpty() && runCatching { write(trimmed) }.getOrDefault(false)
        decision.complete(if (ok) trimmed else null)
    }

    fun decline() = decision.complete(null)
}

/**
 * The desktop half of the phone's autofill service, driving [BridgeServer].
 *
 * The trust model mirrors the phone deliberately, translated to the desktop:
 *
 *  - **Query** (the browser dropdown) returns matching site+username with **no
 *    password**, needing no approval — exactly as the phone shows suggestions
 *    before any biometric.
 *  - **Fill** is the guarded moment. It blocks its worker on an in-app approval
 *    dialog; the phone gates this with a fingerprint, the desktop with an
 *    explicit confirm (and, when [requireMasterConfirm] is on, the master
 *    password). Denial or timeout releases nothing.
 *  - **Capture** always shows the confirm sheet with an editable site name; the
 *    write runs inside the confirmation on the UI thread (see
 *    [BridgeCaptureRequest]).
 *
 * Requests are **queued**, not single-slotted: two tabs asking at once both
 * get their dialog, one after the other, and neither overwrites the other's
 * pending state. The server runs only while the vault is unlocked and the
 * toggle is on; locking or disabling tears the socket down and denies
 * everything still queued.
 */
class BridgeController(
    private val loginsProvider: () -> List<LoginEntity>,
    private val onCaptureConfirmed: (LoginEntity) -> Boolean,
    private val appVersion: String = "1.5.0"
) : BridgeHandler {

    var enabled by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set

    /** Optional hardening: demand the master password at each fill, not just a click. */
    var requireMasterConfirm by mutableStateOf(false)

    var pendingFills by mutableStateOf<List<BridgeFillRequest>>(emptyList())
        private set
    var pendingCaptures by mutableStateOf<List<BridgeCaptureRequest>>(emptyList())
        private set

    /** What the dialogs render: the head of each queue. */
    val pendingFill: BridgeFillRequest? get() = pendingFills.firstOrNull()
    val pendingCapture: BridgeCaptureRequest? get() = pendingCaptures.firstOrNull()

    private var server: BridgeServer? = null
    private val queueLock = Any()

    /** Turn the bridge on/off. Persisted by the caller; only runs while unlocked. */
    fun setEnabled(value: Boolean, unlocked: Boolean) {
        enabled = value
        if (value && unlocked) start() else stop()
    }

    /** Called on unlock: start iff the user had it enabled. */
    fun onUnlocked() { if (enabled) start() }

    /** Called on lock: always tear down, but remember the preference. */
    fun onLocked() {
        stop()
        synchronized(queueLock) {
            pendingFills.forEach { it.deny() }
            pendingFills = emptyList()
            pendingCaptures.forEach { it.decline() }
            pendingCaptures = emptyList()
        }
    }

    private fun start() {
        if (server != null) return
        val srv = BridgeServer(BridgeServer.defaultSocketPath(), this)
        runCatching { srv.start() }
            .onSuccess { server = srv; running = true }
            .onFailure { running = false }
    }

    private fun stop() {
        server?.let { runCatching { it.stop() } }
        server = null
        running = false
    }

    // ── BridgeHandler ────────────────────────────────────────────────────────

    override fun appVersion(): String = appVersion

    override fun onQuery(domain: String): List<BridgeProtocol.Candidate> {
        if (domain.isBlank()) return emptyList()
        return loginsProvider()
            .filter { BridgeMatcher.matches(it.siteName, domain, "") }
            .take(MAX_CANDIDATES)
            .map { BridgeProtocol.Candidate(it.id, it.siteName, it.username) }
    }

    override fun onFill(candidateId: Int, domain: String): Pair<String, String>? {
        val login = loginsProvider().firstOrNull { it.id == candidateId } ?: return null
        // Re-check the match: a stale reqId must not fill a login onto a page it
        // does not belong to.
        if (!BridgeMatcher.matches(login.siteName, domain, "")) return null

        val decision = CompletableFuture<Boolean>()
        val request = BridgeFillRequest(login, domain, decision)
        synchronized(queueLock) { pendingFills = pendingFills + request }
        val approved = runCatching { decision.get(APPROVAL_TIMEOUT_S, TimeUnit.SECONDS) }
            .getOrDefault(false)
        synchronized(queueLock) { pendingFills = pendingFills - request }
        return if (approved) login.username to login.password else null
    }

    override fun onCapture(domain: String, username: String, password: String): String? {
        if (password.isBlank()) return null
        val existing = loginsProvider()
        val suggested = BridgeMatcher.suggestedSiteName(domain, "", null)
        val duplicate = existing.firstOrNull {
            it.username.equals(username.trim(), ignoreCase = true) &&
                BridgeMatcher.matches(it.siteName, domain, "")
        }
        val decision = CompletableFuture<String?>()
        val request = BridgeCaptureRequest(
            suggestedSite = suggested,
            username = username,
            password = password,
            domain = domain,
            duplicateOf = duplicate,
            write = { site ->
                onCaptureConfirmed(
                    LoginEntity(
                        id = duplicate?.id ?: 0,
                        siteName = site,
                        username = username.trim(),
                        password = password
                    )
                )
            },
            decision = decision
        )
        synchronized(queueLock) { pendingCaptures = pendingCaptures + request }
        val site = runCatching { decision.get(APPROVAL_TIMEOUT_S, TimeUnit.SECONDS) }.getOrNull()
        synchronized(queueLock) { pendingCaptures = pendingCaptures - request }
        return site
    }

    private companion object {
        const val MAX_CANDIDATES = 8
        const val APPROVAL_TIMEOUT_S = 110L
    }
}

package com.nikhil.sentinelx.desktop.core.bridge

import com.google.gson.JsonObject
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the app does with each kind of bridge request. Query returns a candidate
 * list carrying NO passwords (it is the browser dropdown — equivalent to the
 * phone's autofill suggestions, which appear without authentication). Fill and
 * capture are the guarded actions: each **blocks** the worker thread until the
 * user resolves an in-app dialog, and returns null / false on denial.
 */
interface BridgeHandler {
    fun onQuery(domain: String): List<BridgeProtocol.Candidate>
    /** Blocks for user approval. Non-null only if approved; then it is the chosen secret. */
    fun onFill(candidateId: Int, domain: String): Pair<String, String>?
    /** Blocks for user confirmation. True only if the user sealed it. */
    fun onCapture(domain: String, username: String, password: String): String?
    fun appVersion(): String

    /**
     * True while the vault is sealed. The server then answers hello honestly
     * (`locked: true`) and refuses everything else with reason "locked" —
     * without this the extension cannot distinguish "app closed" from "vault
     * locked" and shows a misleading "switch the bridge on" for both.
     */
    fun isLocked(): Boolean = false
}

/**
 * A local, user-private Unix-domain-socket server bridging the browser
 * extension's native-messaging host to the running vault.
 *
 * Why a socket, not a network port: it never binds an IP, so nothing off the
 * machine — and no other process on a locked-down runtime dir — can reach it.
 * The socket lives in `$XDG_RUNTIME_DIR` (already `0700`, cleared on logout);
 * the file itself is created `0600`, and the parent app-dir `0700`. It exists
 * only while the vault is unlocked *and* the bridge is switched on — [stop]
 * deletes the socket file.
 *
 * Threading: one acceptor thread, one reader thread per connection — and each
 * *message* is handled on a pool worker, because every browser multiplexes all
 * of its tabs over ONE native-messaging port and therefore one connection. If
 * the reader handled messages inline, a fill dialog left unanswered in one tab
 * would block every query from every other tab for the full approval timeout.
 * Replies are serialised per connection with a write lock.
 */
class BridgeServer(
    private val socketPath: File,
    private val handler: BridgeHandler,
    private val log: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private var channel: ServerSocketChannel? = null
    private var acceptor: Thread? = null
    private var pool: java.util.concurrent.ExecutorService? = null
    private val connections = java.util.Collections.synchronizedSet(HashSet<SocketChannel>())

    val path: File get() = socketPath

    fun start() {
        if (!running.compareAndSet(false, true)) return
        socketPath.parentFile?.let { dir ->
            dir.mkdirs()
            runCatching {
                Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
            }
        }
        // A stale socket from a hard crash would make bind() fail.
        runCatching { Files.deleteIfExists(socketPath.toPath()) }

        val address = UnixDomainSocketAddress.of(socketPath.toPath())
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(address)
        channel = server
        runCatching {
            Files.setPosixFilePermissions(socketPath.toPath(), PosixFilePermissions.fromString("rw-------"))
        }

        pool = java.util.concurrent.Executors.newCachedThreadPool { task ->
            Thread(task, "sentinel-bridge-work").apply { isDaemon = true }
        }
        acceptor = Thread({ acceptLoop(server) }, "sentinel-bridge-accept").apply {
            isDaemon = true
            start()
        }
        log("bridge listening at $socketPath")
    }

    private fun acceptLoop(server: ServerSocketChannel) {
        while (running.get()) {
            val conn = runCatching { server.accept() }.getOrNull() ?: break
            connections.add(conn)
            Thread({ serve(conn) }, "sentinel-bridge-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(conn: SocketChannel) {
        // Interleaved replies from concurrent handlers must not tear each
        // other's lines apart; one lock per connection serialises the writes.
        val writeLock = Any()
        try {
            conn.use {
                val reader = LineReader(conn)
                while (running.get()) {
                    val line = runCatching { reader.readLine() }.getOrNull() ?: break
                    if (line.isBlank()) continue
                    val workers = pool ?: break
                    // Handle off the reader thread: a fill blocked on its
                    // approval dialog must not stop the next tab's query from
                    // being read and answered.
                    runCatching {
                        workers.execute {
                            val reply = runCatching { handle(line) }
                                .getOrElse { BridgeProtocol.error("", it.javaClass.simpleName) }
                                ?: return@execute
                            synchronized(writeLock) {
                                runCatching { writeLine(conn, reply) }
                            }
                        }
                    }
                }
            }
        } finally {
            connections.remove(conn)
        }
    }

    private fun handle(line: String): String? {
        val msg: JsonObject = BridgeProtocol.parse(line)
        val reqId = BridgeProtocol.idOf(msg)
        // One gate for every data-bearing request. hello still answers so the
        // popup can say "unlock SentinelX" instead of "app not reachable".
        if (handler.isLocked() && BridgeProtocol.typeOf(msg) != BridgeProtocol.TYPE_HELLO) {
            return BridgeProtocol.error(reqId, "locked")
        }
        return when (BridgeProtocol.typeOf(msg)) {
            BridgeProtocol.TYPE_HELLO ->
                BridgeProtocol.helloOk(reqId, handler.appVersion(), handler.isLocked())

            BridgeProtocol.TYPE_QUERY -> {
                val domain = BridgeProtocol.str(msg, "domain").orEmpty()
                BridgeProtocol.matches(reqId, handler.onQuery(domain))
            }

            BridgeProtocol.TYPE_FILL -> {
                val id = msg.get("id")?.asInt ?: return BridgeProtocol.error(reqId, "missing id")
                val domain = BridgeProtocol.str(msg, "domain").orEmpty()
                val secret = handler.onFill(id, domain)
                    ?: return BridgeProtocol.error(reqId, "denied")
                BridgeProtocol.secret(reqId, secret.first, secret.second)
            }

            BridgeProtocol.TYPE_CAPTURE -> {
                val domain = BridgeProtocol.str(msg, "domain").orEmpty()
                val username = BridgeProtocol.str(msg, "username").orEmpty()
                val password = BridgeProtocol.str(msg, "password").orEmpty()
                if (password.isBlank()) return BridgeProtocol.error(reqId, "empty")
                val sealedAs = handler.onCapture(domain, username, password)
                    ?: return BridgeProtocol.error(reqId, "declined")
                BridgeProtocol.saved(reqId, sealedAs)
            }

            else -> BridgeProtocol.error(reqId, "unknown type")
        }
    }

    private fun writeLine(conn: SocketChannel, text: String) {
        val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) conn.write(buf)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { channel?.close() }
        // Close live connections too, or their reader threads sit blocked in
        // read() until the browser side goes away on its own.
        synchronized(connections) {
            connections.forEach { runCatching { it.close() } }
            connections.clear()
        }
        pool?.shutdownNow()
        pool = null
        runCatching { Files.deleteIfExists(socketPath.toPath()) }
        channel = null
        acceptor = null
        log("bridge stopped")
    }

    /** Newline-delimited UTF-8 reader over a channel, with a hard per-line cap. */
    private class LineReader(private val conn: SocketChannel) {
        private val buf = ByteBuffer.allocate(8192)
        private val acc = StringBuilder()

        fun readLine(): String? {
            while (true) {
                val nl = acc.indexOf("\n")
                if (nl >= 0) {
                    val line = acc.substring(0, nl)
                    acc.delete(0, nl + 1)
                    return line
                }
                if (acc.length > MAX_LINE) throw IllegalStateException("line too long")
                buf.clear()
                val n = conn.read(buf)
                if (n < 0) return if (acc.isNotEmpty()) acc.toString().also { acc.clear() } else null
                if (n == 0) continue
                buf.flip()
                acc.append(Charsets.UTF_8.decode(buf))
            }
        }

        companion object {
            const val MAX_LINE = 1 shl 20  // 1 MB: a captured password is tiny; anything huge is abuse.
        }
    }

    companion object {
        /** `$XDG_RUNTIME_DIR/sentinelx/bridge.sock`, or a user-home fallback. */
        fun defaultSocketPath(): File {
            val runtime = System.getenv("XDG_RUNTIME_DIR")
            val base = if (!runtime.isNullOrBlank() && File(runtime).isDirectory) File(runtime)
            else File(System.getProperty("java.io.tmpdir"), "sentinelx-" + System.getProperty("user.name"))
            return File(File(base, "sentinelx"), "bridge.sock")
        }
    }
}

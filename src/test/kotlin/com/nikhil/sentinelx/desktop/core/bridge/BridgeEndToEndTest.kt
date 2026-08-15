package com.nikhil.sentinelx.desktop.core.bridge

import com.google.gson.JsonParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the full pipe the browser will use — extension → native-messaging host
 * (Python) → Unix socket → [BridgeServer] → handler — actually round-trips,
 * which is the part no unit test of the Kotlin alone can cover and the part I
 * cannot click through a real browser in this environment.
 *
 * Skips cleanly if `python3` is not on PATH, so the suite still passes on a
 * machine without it (the feature simply won't run there either).
 */
class BridgeEndToEndTest {

    private fun python(): String? =
        System.getenv("PATH")?.split(File.pathSeparator)
            ?.map { File(it, "python3") }
            ?.firstOrNull { it.canExecute() }
            ?.absolutePath

    private class StubHandler : BridgeHandler {
        var lastFillDomain: String? = null
        override fun appVersion() = "test"
        override fun onQuery(domain: String) =
            if (BridgeMatcher.domainCore(domain) == "netflix")
                listOf(BridgeProtocol.Candidate(7, "Netflix", "ray@example.com"))
            else emptyList()
        override fun onFill(candidateId: Int, domain: String): Pair<String, String>? {
            lastFillDomain = domain
            return if (candidateId == 7) "ray@example.com" to "s3cret" else null
        }
        override fun onCapture(domain: String, username: String, password: String) = "Netflix"
    }

    // Native-messaging framing: 4-byte little-endian length + UTF-8 JSON.
    private fun writeFrame(out: java.io.OutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
        out.write(bytes)
        out.flush()
    }

    private fun readFrame(inp: java.io.InputStream): String {
        val lenBuf = inp.readNBytes(4)
        assertEquals(4, lenBuf.size, "host closed before replying")
        val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int
        val body = inp.readNBytes(len)
        return String(body, Charsets.UTF_8)
    }

    @Test
    fun `query and fill round-trip through the python host`() {
        val py = python() ?: run {
            println("python3 not found — skipping bridge end-to-end test")
            return
        }

        val dir = createTempDirectory("bridge-e2e").toFile()
        val socket = File(dir, "bridge.sock")
        val hostScript = File(dir, "sentinelx_host.py")
        // The bundled host is on the test classpath (processResources copies it in).
        BridgeEndToEndTest::class.java.getResourceAsStream("/bridge/sentinelx_host.py")!!
            .use { it.copyTo(hostScript.outputStream()) }

        val handler = StubHandler()
        val server = BridgeServer(socket, handler)
        server.start()
        try {
            val process = ProcessBuilder(py, hostScript.absolutePath)
                .redirectErrorStream(false)
                .also { it.environment()["SENTINELX_BRIDGE_SOCKET"] = socket.absolutePath }
                .start()

            process.outputStream.use { stdin ->
                process.inputStream.use { stdout ->
                    // 1. Query → matches (no password on the wire).
                    writeFrame(stdin, """{"type":"query","reqId":"1","domain":"www.netflix.com"}""")
                    val matches = JsonParser.parseString(readFrame(stdout)).asJsonObject
                    assertEquals("matches", matches.get("type").asString)
                    val candidates = matches.getAsJsonArray("candidates")
                    assertEquals(1, candidates.size())
                    val first = candidates[0].asJsonObject
                    assertEquals("Netflix", first.get("siteName").asString)
                    assertTrue(!first.has("password"), "candidate must not carry a password")

                    // 2. Fill → secret, and the handler saw the domain.
                    writeFrame(stdin, """{"type":"fill","reqId":"2","id":7,"domain":"www.netflix.com"}""")
                    val secret = JsonParser.parseString(readFrame(stdout)).asJsonObject
                    assertEquals("secret", secret.get("type").asString)
                    assertEquals("s3cret", secret.get("password").asString)
                    assertEquals("www.netflix.com", handler.lastFillDomain)
                }
            }
            process.waitFor()
        } finally {
            server.stop()
            dir.deleteRecursively()
        }
    }
}

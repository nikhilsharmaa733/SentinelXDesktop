package com.nikhil.sentinelx.desktop.core.bridge

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The line protocol spoken over the local socket between the browser extension
 * (via its native-messaging host) and the running desktop app.
 *
 * One JSON object per message, newline-terminated. Deliberately tiny and
 * hand-mapped rather than reflection-serialised, so the exact wire shape is
 * visible in one place and the host script (Python) and the app (Kotlin) can't
 * drift. The **password never appears in a query or a match** — a fill result
 * carries it only after the user has approved that specific request in-app, and
 * it crosses a local socket that never touches the network.
 */
object BridgeProtocol {
    const val PROTOCOL_VERSION = 1

    // Requests (extension → app)
    const val TYPE_HELLO = "hello"       // handshake + extension identity
    const val TYPE_QUERY = "query"       // "what logins match this page?"
    const val TYPE_FILL = "fill"         // "the user picked this one — give me the secret"
    const val TYPE_CAPTURE = "capture"   // "a new credential was submitted here"

    // Responses (app → extension)
    const val TYPE_HELLO_OK = "hello_ok"
    const val TYPE_MATCHES = "matches"   // candidate list, WITHOUT passwords
    const val TYPE_SECRET = "secret"     // the approved credential
    const val TYPE_SAVED = "saved"
    const val TYPE_ERROR = "error"

    /** A candidate shown in the browser dropdown — never carries the password. */
    data class Candidate(val id: Int, val siteName: String, val username: String)

    private val gson = Gson()

    fun parse(line: String): JsonObject = JsonParser.parseString(line).asJsonObject

    fun typeOf(msg: JsonObject): String = msg.get("type")?.asString ?: ""
    fun idOf(msg: JsonObject): String = msg.get("reqId")?.asString ?: ""
    fun str(msg: JsonObject, key: String): String? = msg.get(key)?.takeIf { !it.isJsonNull }?.asString

    private fun envelope(type: String, reqId: String): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("reqId", reqId)
        addProperty("v", PROTOCOL_VERSION)
    }

    fun helloOk(reqId: String, appVersion: String, locked: Boolean = false): String =
        envelope(TYPE_HELLO_OK, reqId).apply {
            addProperty("app", "SentinelX")
            addProperty("version", appVersion)
            // Lets the extension popup tell "vault is locked" apart from "app is
            // not running" — the two used to collapse into one misleading error.
            addProperty("locked", locked)
        }.toString()

    fun matches(reqId: String, candidates: List<Candidate>): String =
        envelope(TYPE_MATCHES, reqId).apply {
            add("candidates", gson.toJsonTree(candidates))
        }.toString()

    fun secret(reqId: String, username: String, password: String): String =
        envelope(TYPE_SECRET, reqId).apply {
            addProperty("username", username)
            addProperty("password", password)
        }.toString()

    fun saved(reqId: String, siteName: String): String =
        envelope(TYPE_SAVED, reqId).apply {
            addProperty("siteName", siteName)
        }.toString()

    fun error(reqId: String, reason: String): String =
        envelope(TYPE_ERROR, reqId).apply {
            addProperty("reason", reason)
        }.toString()
}

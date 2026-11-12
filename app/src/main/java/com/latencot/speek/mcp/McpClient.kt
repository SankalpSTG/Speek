package com.latencot.speek.mcp

import com.latencot.speek.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * A minimal MCP client over Streamable HTTP: one JSON-RPC request per POST, answered as JSON or
 * as a short SSE stream. Enough for an agent's tools (`tools/call`), which is all Speek needs.
 */
class McpClient(private val endpoint: String, private val token: String) {

    class McpException(message: String) : Exception(message)

    private val ids = AtomicLong(1)
    private var sessionId: String? = null
    private var protocol: String? = null
    private var initialized = false

    /** The server's name and version, after the MCP handshake. */
    suspend fun connect(): String {
        val result = request(
            "initialize",
            JSONObject()
                .put("protocolVersion", PROTOCOL)
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "speek").put("version", BuildConfig.VERSION_NAME)),
        )
        protocol = result.getString("protocolVersion")
        notify("notifications/initialized")
        initialized = true
        val info = result.getJSONObject("serverInfo")
        return "${info.getString("name")} ${info.getString("version")}"
    }

    /** The names of every tool this server offers: optional screens show only for tools it has. */
    suspend fun toolNames(): Set<String> {
        if (!initialized) connect()
        val tools = request("tools/list", JSONObject()).getJSONArray("tools")
        return (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
    }

    /** Whether [tool]'s input schema has [argument]: what this server's tools can take. */
    suspend fun accepts(tool: String, argument: String): Boolean {
        if (!initialized) connect()
        val tools = request("tools/list", JSONObject()).getJSONArray("tools")
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            if (t.getString("name") == tool) {
                return t.getJSONObject("inputSchema").optJSONObject("properties")?.has(argument) == true
            }
        }
        return false
    }

    /** Calls a tool and returns its text. A tool that reports an error throws. */
    suspend fun call(tool: String, arguments: JSONObject = JSONObject()): String =
        text(content(tool, arguments))

    /** Calls a tool that answers with one embedded resource; returns its bytes. */
    suspend fun resource(tool: String, arguments: JSONObject): ByteArray {
        val content = content(tool, arguments)
        for (i in 0 until content.length()) {
            val part = content.getJSONObject(i)
            if (part.getString("type") == "resource") {
                return Base64.getDecoder().decode(part.getJSONObject("resource").getString("blob"))
            }
        }
        throw McpException("$tool: no file in the answer")
    }

    private suspend fun content(tool: String, arguments: JSONObject): JSONArray {
        if (!initialized) connect()
        val result = request("tools/call", JSONObject().put("name", tool).put("arguments", arguments))
        val content = result.getJSONArray("content")
        if (result.optBoolean("isError")) throw McpException("$tool: ${text(content)}")
        return content
    }

    private fun text(content: JSONArray): String = buildString {
        for (i in 0 until content.length()) {
            val part = content.getJSONObject(i)
            if (part.getString("type") == "text") append(part.getString("text"))
        }
    }

    private suspend fun request(method: String, params: JSONObject): JSONObject {
        val id = ids.getAndIncrement()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val reply = post(body) ?: throw McpException("$method: empty answer")
        if (reply.has("error")) {
            val e = reply.getJSONObject("error")
            throw McpException("$method: ${e.optString("message")} (${e.optInt("code")})")
        }
        return reply.getJSONObject("result")
    }

    private suspend fun notify(method: String) {
        post(JSONObject().put("jsonrpc", "2.0").put("method", method))
    }

    private suspend fun post(body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            conn.setRequestProperty("Authorization", "Bearer $token")
            protocol?.let { conn.setRequestProperty("MCP-Protocol-Version", it) }
            sessionId?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            conn.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
            when {
                code == 401 || code == 403 -> throw McpException("The token was refused ($code).")
                code == 202 -> null
                code !in 200..299 -> throw McpException("Server answered HTTP $code.")
                else -> {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val type = conn.contentType.orEmpty()
                    if (type.startsWith("text/event-stream")) lastSseMessage(text) else JSONObject(text)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** The JSON-RPC response in an SSE body: the last `data:` event that carries an id. */
    private fun lastSseMessage(stream: String): JSONObject {
        return stream.split("\n\n")
            .mapNotNull { event ->
                event.lines().filter { it.startsWith("data:") }
                    .joinToString("\n") { it.removePrefix("data:").trim() }
                    .takeIf { it.isNotEmpty() }
            }
            .map { JSONObject(it) }
            .lastOrNull { it.has("id") }
            ?: throw McpException("The event stream held no response.")
    }

    companion object {
        const val PROTOCOL = "2025-06-18"
    }
}

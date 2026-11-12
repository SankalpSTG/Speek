package com.latencot.speek.data

import android.content.Context
import com.latencot.speek.mcp.McpClient
import org.json.JSONArray
import org.json.JSONObject

/** What Speek keeps on the phone: the connection, the switches, what you have seen. */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("speek", Context.MODE_PRIVATE)

    data class Connection(val endpoint: String, val token: String)

    /** Null until both the endpoint and the token are set: Speek has no built-in server. */
    var connection: Connection?
        get() {
            val endpoint = prefs.getString("endpoint", null) ?: return null
            val token = prefs.getString("token", null) ?: return null
            return Connection(endpoint, token)
        }
        set(value) {
            requireNotNull(value)
            prefs.edit().putString("endpoint", value.endpoint).putString("token", value.token).remove("serverName").apply()
        }

    /** Send a spoken message as soon as you stop talking, instead of waiting for Send. */
    var sendWhenDone: Boolean
        get() = prefs.getBoolean("sendWhenDone", true)
        set(value) = prefs.edit().putBoolean("sendWhenDone", value).apply()

    /** Read new messages from the agent aloud while Speek is open. */
    var readAloud: Boolean
        get() = prefs.getBoolean("readAloud", true)
        set(value) = prefs.edit().putBoolean("readAloud", value).apply()

    /** The connected server's name (its `capabilities` name): who calls and messages. Loaded by
     * [loadServerName] before anything can ring or notify, so it is always set when read. */
    val serverName: String
        get() = prefs.getString("serverName", null) ?: error("server name not loaded yet")

    /** Reads the server's name from `capabilities` and keeps it. Called each time the app or the
     * background listener starts, so a renamed server shows its new name. */
    suspend fun loadServerName(client: McpClient) {
        val name = JSONObject(client.call("capabilities")).getString("name")
        prefs.edit().putString("serverName", name).apply()
    }

    /** Stay connected in the background so the agent's calls ring (a quiet ongoing notification). */
    var listen: Boolean
        get() = prefs.getBoolean("listen", true)
        set(value) = prefs.edit().putBoolean("listen", value).apply()

    /** Per Claude Code session (short id), its `updated` time when you last had it open. */
    var liveSeen: Map<String, String>
        get() = JSONObject(prefs.getString("ccSeen", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
        set(value) = prefs.edit().putString("ccSeen", JSONObject(value).toString()).apply()

    /** Keys of the agent notifications you have seen; null before the first feed read. */
    var read: Set<String>?
        get() = prefs.getString("read", null)?.let { s -> JSONArray(s).let { a -> (0 until a.length()).map(a::getString).toSet() } }
        set(value) = prefs.edit().putString("read", JSONArray(requireNotNull(value).toList()).toString()).apply()
}

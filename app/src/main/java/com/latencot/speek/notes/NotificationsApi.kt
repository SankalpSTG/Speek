package com.latencot.speek.notes

import com.latencot.speek.mcp.McpClient
import org.json.JSONArray
import org.json.JSONObject

/** One notification the server or one of its runs sent (MCP `notifications`). */
data class Notification(
    val id: String,
    val created: String,
    val kind: String,
    /** urgent, normal, quiet or unrated. */
    val importance: String,
    val text: String,
    val files: List<String>,
    val ringAt: String?,
    /** Who sent it: the run that sent it, or the server itself. */
    val from: String,
    /** Buttons that do something here: label and the data for `notification_button`. */
    val buttons: List<Pair<String, String>>,
    val replies: Int,
    /** none, idle, working (its run is answering) or failed. */
    val threadStatus: String,
)

/** One line of a notification's thread: your reply, or the run's answer. */
data class ThreadEntry(val at: String, val fromMe: Boolean, val text: String)

data class Thread(val notification: Notification, val entries: List<ThreadEntry>, val status: String)

/** The server's notification tools. A reply goes to the run that sent the notification. */
class NotificationsApi(private val mcp: McpClient) {

    suspend fun list(limit: Int = 100): List<Notification> {
        val arr = JSONArray(mcp.call("notifications", JSONObject().put("limit", limit)))
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    suspend fun thread(id: String): Thread {
        val o = JSONObject(mcp.call("notification", JSONObject().put("id", id)))
        val e = o.getJSONArray("entries")
        return Thread(
            notification = parse(o.getJSONObject("notification")),
            entries = (0 until e.length()).map {
                val x = e.getJSONObject(it)
                ThreadEntry(x.getString("at"), x.getString("from") == "owner", x.getString("text"))
            },
            status = o.getString("status"),
        )
    }

    /** Returns the server's note on where the reply went. */
    suspend fun reply(id: String, text: String): String =
        mcp.call("notification_reply", JSONObject().put("id", id).put("text", text))

    suspend fun press(data: String): String =
        mcp.call("notification_button", JSONObject().put("data", data))

    private fun parse(o: JSONObject): Notification {
        val files = o.getJSONArray("files")
        val buttons = o.getJSONArray("buttons")
        val thread = o.getJSONObject("thread")
        return Notification(
            id = o.getString("id"),
            created = o.getString("created"),
            kind = o.getString("kind"),
            importance = o.getString("importance"),
            text = o.getString("text"),
            files = (0 until files.length()).map { files.getString(it) },
            ringAt = if (o.isNull("ring_at")) null else o.getString("ring_at"),
            from = o.getString("from"),
            buttons = (0 until buttons.length()).map {
                val b = buttons.getJSONArray(it)
                b.getString(0) to b.getString(1)
            },
            replies = thread.getInt("replies"),
            threadStatus = thread.getString("status"),
        )
    }
}

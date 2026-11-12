package com.latencot.speek.data

import org.json.JSONArray
import org.json.JSONObject

/** Turns the text the server's MCP tools answer with into structured rows. */
object Parse {

    // Seconds are optional: with them a quick answer sorts after the question it answers.
    private val header = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}(?::\d{2})?)] ([a-z_]+):$""")
    private val sessionTag = Regex("""^\[#(\d+)(?: [^\]]*)?]\s*""")
    private val META = listOf("file: ", "ring: ", "importance: ")

    /**
     * `feed` text: blocks of `[YYYY-MM-DD HH:MM] kind:` followed by the message, newest first.
     * Returns them oldest first. A session's answer starts with its tag (`[#3 label] `), which
     * becomes [Line.session]. A message's own lines never match the header (that would need a
     * bracketed timestamp plus a bare kind, alone on a line).
     */
    fun feed(text: String): List<Line> {
        val lines = mutableListOf<Line>()
        var at: String? = null
        var kind = ""
        val body = StringBuilder()
        fun flush() {
            val start = at ?: return
            // A call's `ring: <time>`, its `file: <path>` lines and `importance: <level>` come at the end.
            val all = body.toString().trim().lines()
            val tail = all.indices.reversed().takeWhile { line -> META.any { all[line].startsWith(it) } }.lastOrNull()
            val meta = tail?.let { all.subList(it, all.size) }.orEmpty()
            val files = meta.filter { it.startsWith("file: ") }.map { it.removePrefix("file: ").trim() }
            val ringAt = meta.firstOrNull { it.startsWith("ring: ") }?.removePrefix("ring: ")?.trim()
            val importance = meta.firstOrNull { it.startsWith("importance: ") }?.removePrefix("importance: ")?.trim()
            val msg = (if (tail != null) all.subList(0, tail) else all).joinToString("\n").trim()
            val tag = sessionTag.find(msg)
            lines += Line(
                // Keyed by the minute, so keys stay the same whether or not the feed has seconds.
                key = "${start.take(16)}|$kind|${(msg + files).hashCode()}",
                at = start,
                fromMe = false,
                kind = kind,
                text = if (tag != null) msg.substring(tag.range.last + 1) else msg,
                session = tag?.groupValues?.get(1),
                files = files,
                ringAt = ringAt,
                importance = importance,
            )
        }
        for (raw in text.lines()) {
            val m = header.matchEntire(raw)
            if (m != null) {
                flush()
                at = m.groupValues[1]
                kind = m.groupValues[2]
                body.clear()
            } else if (at != null) {
                body.append(raw).append('\n')
            }
        }
        flush()
        return lines.reversed()
    }

    /**
     * `wait` with format json: {cursor, messages: [{id, kind, text, importance, ring_at,
     * session, thread, files}]}, newest first. Lines come oldest first, keyed exactly as [feed]
     * keys them, so a call already handled from the text feed never rings again. `answered`:
     * call ids another phone stopped (empty from a server without it).
     */
    fun waitJson(text: String): Waited {
        val o = JSONObject(text)
        val ans = o.optJSONArray("answered") ?: JSONArray()
        return Waited(o.getString("cursor"), messages(o.getJSONArray("messages")), (0 until ans.length()).map(ans::getString))
    }

    data class Waited(val cursor: String, val lines: List<Line>, val answered: List<String>)

    /** `feed` with format json: same shape as [waitJson]; oldest first, with ids. */
    fun feedJson(text: String): List<Line> = messages(JSONObject(text).getJSONArray("messages"))

    private fun messages(arr: JSONArray): List<Line> {
        val lines = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            val id = m.getString("id")
            val msg = m.getString("text").trim()
            val filesArr = m.getJSONArray("files")
            val files = (0 until filesArr.length()).map(filesArr::getString)
            // The outbox id is YYYYMMDD-HHMMSS-xxxxxx: the feed's timestamp.
            val at = "${id.substring(0, 4)}-${id.substring(4, 6)}-${id.substring(6, 8)} " +
                "${id.substring(9, 11)}:${id.substring(11, 13)}:${id.substring(13, 15)}"
            val tag = sessionTag.find(msg)
            Line(
                key = "${at.take(16)}|${m.getString("kind")}|${(msg + files).hashCode()}",
                at = at,
                fromMe = false,
                kind = m.getString("kind"),
                text = if (tag != null) msg.substring(tag.range.last + 1) else msg,
                session = tag?.groupValues?.get(1),
                files = files,
                ringAt = if (m.isNull("ring_at")) null else m.getString("ring_at"),
                importance = m.optString("importance").ifEmpty { null },
                id = id,
                thread = if (m.isNull("thread")) null else m.getString("thread"),
            )
        }
        return lines.reversed()
    }

    /** `wait` text: `cursor: <id>`, then new messages in feed form. */
    fun wait(text: String): Pair<String, List<Line>> {
        val first = text.lineSequence().first()
        require(first.startsWith("cursor: ")) { "not a wait answer: $first" }
        return first.removePrefix("cursor: ").trim() to feed(text.substringAfter('\n', ""))
    }
}

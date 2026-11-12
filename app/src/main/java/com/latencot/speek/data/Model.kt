package com.latencot.speek.data

/** One bubble: something the agent sent, or something you said. */
data class Line(
    val key: String,
    /** "yyyy-MM-dd HH:mm:ss" (older feeds: "yyyy-MM-dd HH:mm"), server clock (agent) or phone's (you). */
    val at: String,
    val fromMe: Boolean,
    /** The agent's message kind (session, reply, blocker, alert, digest, status); "you" for yours. */
    val kind: String,
    /** The text, without the session tag. */
    val text: String,
    /** The session number this belongs to; null for notifications outside any session. */
    val session: String?,
    /**
     * Attached files. The agent's: paths on its machine (fetched with the `file` tool) or
     * http(s) URLs. Yours: the copies Speek keeps on the phone.
     */
    val files: List<String> = emptyList(),
    /** A call's ring time, "yyyy-MM-dd HH:mm"; null rings at once. Only kind "call" has one. */
    val ringAt: String? = null,
    /**
     * How important the server rated it: urgent (special tone), normal, quiet (no sound), or
     * unrated (rating failed). Null when the server does not rate messages, and for yours.
     */
    val importance: String? = null,
    /** The server's outbox id (from `wait` in JSON); null from the text feed. */
    val id: String? = null,
    /** Set when this is an answer inside a notification's thread: that notification's id. */
    val thread: String? = null,
) {
    /** A one-line summary for previews and speech when there is no text. */
    val summary: String
        get() = text.lineSequence().firstOrNull { it.isNotBlank() }
            ?: files.singleOrNull()?.let { if (Media.isImage(it)) "Photo" else "File: ${Media.name(it)}" }
            ?: if (files.isNotEmpty()) "${files.size} files" else ""
}

/** One row of the chat list: a Claude Code session ("cc:ID") or an anonymous chat ("fp:N"). */
data class Chat(
    val ref: String,
    val name: String,
    val preview: String,
    val previewFromMe: Boolean,
    /** Last activity, "yyyy-MM-dd HH:mm"; empty when the chat has no messages here. */
    val at: String,
    /** 1 when the chat has anything new (shown as a dot). */
    val unread: Int,
    val working: Boolean,
    /** A session waiting for your Allow / Deny. */
    val waiting: Boolean = false,
    val live: Boolean = false,
)

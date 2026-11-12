package com.latencot.speek.data

/** Builds the chat lists: the Claude Code sessions, and the anonymous chats. */
object Chats {

    /**
     * [seen]: per session, the `updated` time you last saw it at; newer means unread.
     * [waitingOn]: sessions with a permission request waiting for you.
     */
    fun of(live: List<LiveSession>, seen: Map<String, String>, waitingOn: Set<String>): List<Chat> =
        live.map { s ->
            val waiting = s.id in waitingOn || s.status == "waiting"
            Chat(
                ref = s.ref,
                name = s.name,
                preview = if (waiting) "Needs your OK" else Markdown.oneLine(s.last).ifEmpty { s.cwd.replace(HOME, "~") },
                previewFromMe = false,
                at = s.updated,
                unread = if (s.updated > (seen[s.id] ?: "")) 1 else 0,
                working = s.status == "working",
                waiting = waiting,
                live = true,
            )
        }.sortedByDescending { it.at }

    /** The anonymous list: background sessions the server runs, latest activity first. */
    fun anon(sessions: List<AnonSession>, seen: Map<String, String>): List<Chat> = sessions.map { s ->
        Chat(
            ref = s.ref,
            name = s.name,
            preview = when {
                s.status == "failed" -> "The last turn failed; send anything to retry"
                else -> Markdown.oneLine(s.last.removePrefix("You: ")).ifEmpty { "No messages yet" }
            },
            previewFromMe = s.last.startsWith("You: "),
            at = s.updated,
            unread = if (s.updated > (seen[s.ref] ?: "")) 1 else 0,
            working = s.status == "working",
            waiting = s.status == "failed",
            live = true,
        )
    }.sortedByDescending { it.at }

    private val HOME = Regex("""^/(?:home|Users)/[^/]+""")
}

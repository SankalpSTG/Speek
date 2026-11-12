package com.latencot.speek.calls

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.latencot.speek.R
import com.latencot.speek.data.AnonSession
import com.latencot.speek.data.Line
import com.latencot.speek.data.LiveSession
import com.latencot.speek.data.Markdown
import com.latencot.speek.data.Store

/**
 * Phone notifications for what arrives while Speek is not on screen: the server's messages
 * (one notification per chat, its newest lines stacked) and a background Claude Code session that
 * finished its turn. Calls ring and permission requests notify on their own (see [Calls]).
 */
object Notes {

    /** Speek is on screen: it shows (and reads aloud) what arrives, so nothing notifies. */
    @Volatile
    var visible = false

    private const val SHOWN = 6
    private val stacked = mutableMapOf<String, MutableList<String>>()
    private val stackedIds = mutableMapOf<String, MutableList<String?>>()
    private const val NOTES = "notes"

    /** A new feed message from `wait`. Each key notifies once. */
    fun message(context: Context, line: Line) {
        if (line.fromMe || line.kind == "call" || line.kind == "ask") return
        if (!CallBook(context).claim("note:${line.key}")) return
        if (visible) return
        // A background session's answer (kind "session") opens its anonymous chat; everything else
        // is a notification (one stack). A "[#N ...]" tag in any other message is just text.
        val session = line.session?.takeIf { line.kind == "session" }
        val ref = if (session != null) "${AnonSession.PREFIX}$session" else NOTES
        val title = when {
            line.kind == "app" -> "New Speek build"
            line.thread != null -> "Reply"
            session != null -> "${Store(context).serverName} #$session"
            else -> Store(context).serverName
        }
        val text = Markdown.oneLine(line.summary).ifEmpty { line.kind }
        val channel = when (line.importance) {
            "urgent" -> Calls.CH_URGENT
            "quiet" -> Calls.CH_QUIET
            else -> Calls.CH_MESSAGES // normal, unrated (rating failed), or not rated at all
        }
        post(context, ref, title, text, channel, line.thread ?: line.id)
    }

    /** A background session you can talk to from Speek went from working to done. */
    fun sessionDone(context: Context, s: LiveSession) {
        if (visible || s.inTerminal) return
        if (!CallBook(context).claim("done:${s.id}|${s.updated}")) return
        post(context, s.ref, s.name, Markdown.oneLine(s.last).ifEmpty { "Finished its turn" }, Calls.CH_SESSIONS)
    }

    /** Speek came on screen: what it would notify is on screen now. */
    @Synchronized
    fun clearAll(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        stacked.keys.forEach { nm.cancel(id(it)) }
        stacked.clear()
        stackedIds.clear()
    }

    @Synchronized
    private fun post(context: Context, ref: String, title: String, text: String, channel: String, note: String? = null) {
        Calls.channels(context)
        val lines = stacked.getOrPut(ref) { mutableListOf() }.apply { add(text) }
        val ids = stackedIds.getOrPut(ref) { mutableListOf() }.apply { add(note) }
        while (lines.size > SHOWN) lines.removeAt(0)
        val style = NotificationCompat.InboxStyle().also { s -> lines.forEach(s::addLine) }
        val note = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_speek)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(if (lines.size == 1) NotificationCompat.BigTextStyle().bigText(text) else style)
            .setNumber(lines.size)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (channel == Calls.CH_QUIET) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setColor(if (channel == Calls.CH_URGENT) 0xFFB91C1C.toInt() else 0xFF2563EB.toInt())
            // One notification opens its thread; several open the bell list.
            .setContentIntent(
                if (ref == NOTES) Calls.noteIntent(context, ids.distinct().singleOrNull(), id(ref))
                else Calls.chatIntent(context, ref, id(ref)),
            )
            .setAutoCancel(true)
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(id(ref), note)
        }
    }

    private fun id(ref: String) = "chat:$ref".hashCode()
}

package com.latencot.speek.calls

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.latencot.speek.MainActivity
import com.latencot.speek.R
import com.latencot.speek.data.Line
import com.latencot.speek.data.LiveParse
import com.latencot.speek.data.Store
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What happens to a call: ring now, ring at its time, or (too late to ring) a missed call. */
object Calls {

    /** A call this late (from when it was sent, or when it was due) is shown as missed instead. */
    val LATE: Duration = Duration.ofMinutes(30)

    const val CH_CALLS = "calls"
    const val CH_MISSED = "missed"
    const val CH_LISTEN = "listen"
    const val CH_ASKS = "asks"
    const val CH_MESSAGES = "messages"
    /** The server rated it urgent: its own tone, vibration and light (a channel's sound is fixed once made). */
    const val CH_URGENT = "urgent"
    /** The server rated it quiet: no sound, no pop-up. */
    const val CH_QUIET = "quiet"
    const val CH_SESSIONS = "sessions"
    const val EXTRA_CHAT = "chat"
    /** Open this notification (its thread); with [EXTRA_FOCUS] the reply box is focused. */
    const val EXTRA_NOTE = "note"
    const val EXTRA_FOCUS = "focus"
    /** Open the notifications list (the bell). */
    const val EXTRA_BELL = "bell"

    private val minute = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun parse(at: String): LocalDateTime = LocalDateTime.parse(at.take(16), minute)

    fun of(line: Line) = Call(line.key, line.text, line.session, line.ringAt, line.id)

    /** A new call message (from `wait`, or the open app's feed). Each key is handled once. */
    fun handle(context: Context, line: Line) {
        if (line.kind != "call" || !CallBook(context).claim(line.key)) return
        decide(context, of(line), sent = parse(line.at))
    }

    /** A Claude Code session waits for your Allow / Deny: a notification that opens its chat (once per message). */
    fun ask(context: Context, line: Line) {
        if (line.kind != "ask" || !CallBook(context).claim(line.key)) return
        val id = LiveParse.askSession(line.text) ?: return
        channels(context)
        val text = LiveParse.askText(line.text)
        val note = NotificationCompat.Builder(context, CH_URGENT)
            .setSmallIcon(R.drawable.ic_stat_speek)
            .setContentTitle("A session needs your OK")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(chatIntent(context, "cc:$id", line.key.hashCode()))
            .setAutoCancel(true)
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(line.key.hashCode(), note)
        }
    }

    /** Messages seen for the first time ever: old calls never ring, future ones are scheduled. */
    fun catchUp(context: Context, lines: List<Line>) {
        val book = CallBook(context)
        lines.filter { it.kind == "call" }.forEach { line ->
            if (!book.claim(line.key)) return@forEach
            val ring = line.ringAt?.let(::parse)
            if (ring != null && ring.isAfter(LocalDateTime.now())) schedule(context, of(line), millis(ring))
        }
    }

    /** After a reboot or an update: alarms are gone, set them again (or ring / miss the overdue). */
    fun restore(context: Context) {
        val now = System.currentTimeMillis()
        CallBook(context).scheduled.forEach { (call, due) ->
            when {
                due > now -> schedule(context, call, due)
                now - due <= LATE.toMillis() -> schedule(context, call, now + SOON_MS)
                else -> {
                    CallBook(context).unschedule(call.key)
                    missed(context, call)
                }
            }
        }
    }

    /**
     * Calls another of your phones stopped: stop ringing them here, and drop their alarms (a call that
     * has not rung yet, or is set for later). Repeats every wait; each step does nothing twice.
     */
    fun answered(context: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        RingService.stopAnswered(context, ids.toSet())
        val book = CallBook(context)
        book.scheduled.filter { it.first.id in ids }.forEach { (call, _) ->
            book.unschedule(call.key)
            context.getSystemService(AlarmManager::class.java).cancel(alarm(context, call.key))
        }
    }

    /** Rings [call] in [delayMs]: Speek's own test call. */
    fun ringIn(context: Context, call: Call, delayMs: Long) = schedule(context, call, System.currentTimeMillis() + delayMs)

    private fun decide(context: Context, call: Call, sent: LocalDateTime) {
        val now = LocalDateTime.now()
        val ring = call.ringAt?.let(::parse)
        when {
            ring != null && ring.isAfter(now) -> schedule(context, call, millis(ring))
            // Even "now" goes through an alarm clock: the one way Android always lets a
            // background app start ringing.
            Duration.between(ring ?: sent, now) <= LATE -> schedule(context, call, System.currentTimeMillis() + SOON_MS)
            else -> missed(context, call)
        }
    }

    private fun millis(at: LocalDateTime) = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private const val SOON_MS = 500L

    private fun schedule(context: Context, call: Call, millis: Long) {
        CallBook(context).schedule(call, millis)
        // setAlarmClock: exact even in Doze, shown as the next alarm, and lets the ring start.
        context.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(millis, callIntent(context, call)), alarm(context, call.key))
    }

    private fun alarm(context: Context, key: String): PendingIntent = PendingIntent.getBroadcast(
        context, key.hashCode(),
        Intent(context, AlarmReceiver::class.java).putExtra(AlarmReceiver.EXTRA_KEY, key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun missed(context: Context, call: Call) {
        channels(context)
        val n = NotificationCompat.Builder(context, CH_MISSED)
            .setSmallIcon(R.drawable.ic_stat_speek)
            .setContentTitle("Missed call from ${Store(context).serverName}")
            .setContentText(call.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(call.text))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(callIntent(context, call))
            .setAutoCancel(true)
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(call.key.hashCode(), n)
        }
    }

    /** Android's settings for one of Speek's notification categories. */
    fun channelSettings(context: Context, channel: String): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens one notification's thread, or the bell list when [id] is null. */
    fun noteIntent(context: Context, id: String?, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (id != null) putExtra(EXTRA_NOTE, id) else putExtra(EXTRA_BELL, true) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** A call opens its own notification; a session's call opens that session. */
    fun callIntent(context: Context, call: Call): PendingIntent =
        when {
            call.id != null -> noteIntent(context, call.id, call.key.hashCode())
            call.session != null -> chatIntent(context, call.session, call.key.hashCode())
            else -> noteIntent(context, null, call.key.hashCode())  // the bell list, never the home screen
        }

    /** Opens Speek on a chat ("cc:ID", or an anonymous chat's session number), or on the chat list. */
    fun chatIntent(context: Context, session: String?, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_CHAT, session),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Calls from your agents: rings until you stop it"
            setSound(null, null) // the ring service plays the melody itself, looped
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT))
        nm.deleteNotificationChannel(CH_ASKS) // retired: asks ring on the Urgent channel
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Messages from the server"
        })
        nm.createNotificationChannel(NotificationChannel(CH_URGENT, "Urgent", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "What the server rates urgent (people replying, deadlines) and sessions waiting for your OK"
            setSound(
                Uri.parse("android.resource://${context.packageName}/${R.raw.urgent}"),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 120, 80, 120, 80, 400)
            enableLights(true)
            lightColor = Color.RED
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, "Quiet", NotificationManager.IMPORTANCE_LOW).apply {
            description = "What the server rates quiet: errors it handles, digests, receipts. No sound"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_SESSIONS, "Session replies", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A background Claude Code session finished its turn"
        })
        nm.createNotificationChannel(NotificationChannel(CH_LISTEN, "Listening for calls", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Android shows this while Speek stays connected. Switch it off: Speek keeps listening"
            setShowBadge(false)
        })
    }
}

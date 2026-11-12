package com.latencot.speek.calls

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.latencot.speek.R
import com.latencot.speek.data.LiveParse
import com.latencot.speek.data.Parse
import com.latencot.speek.data.Store
import com.latencot.speek.mcp.McpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Keeps one `wait` open to the agent at all times, so a call rings even with Speek closed and
 * the phone locked. Restarted by the system if killed, and after a reboot.
 */
class ListenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Calls.channels(this)
        // Android requires this while Speek holds the connection; "Hide" opens its category,
        // and with the category off it is never shown while Speek keeps listening.
        val hide = PendingIntent.getActivity(this, HIDE, Calls.channelSettings(this, Calls.CH_LISTEN), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Calls.CH_LISTEN)
            .setSmallIcon(R.drawable.ic_stat_speek)
            .setContentTitle("Speek is connected")
            .setContentText("Tap Hide and switch this category off; messages and calls still come.")
            .setContentIntent(Calls.chatIntent(this, null, 0))
            .addAction(0, "Hide", hide)
            .setSilent(true)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val conn = Store(this).connection
        if (conn == null || !Store(this).listen) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) {
            val client = McpClient(conn.endpoint, conn.token)
            job = scope.launch {
                launch { sessions(client) }
                listen(client)
            }
        }
        return START_STICKY
    }

    private suspend fun listen(client: McpClient) {
        val book = CallBook(this)
        var backoff = MIN_BACKOFF
        var named = false
        while (scope.isActive) {
            try {
                if (!named) {
                    Store(this).loadServerName(client)
                    named = true
                }
                if (book.cursor.isEmpty()) {
                    // First connection: nothing old rings; calls set for later are scheduled.
                    val (cursor, _) = Parse.wait(client.call("wait", JSONObject().put("cursor", "").put("timeout", 1)))
                    Calls.catchUp(this, Parse.feedJson(client.call("feed", JSONObject().put("limit", 100).put("format", "json"))))
                    book.cursor = cursor
                }
                val (cursor, lines, answered) = Parse.waitJson(client.call("wait",
                    JSONObject().put("cursor", book.cursor).put("timeout", WAIT_S).put("format", "json")))
                lines.forEach {
                    Calls.handle(this, it)
                    Calls.ask(this, it)
                    Notes.message(this, it)
                }
                // After handle: a call answered on another phone before this one rang is cancelled too.
                Calls.answered(this, answered)
                book.cursor = cursor
                backoff = MIN_BACKOFF
            } catch (e: Exception) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
            }
        }
    }

    /** Watches the background Claude Code sessions: one that stops working has answered, so it notifies. */
    private suspend fun sessions(client: McpClient) {
        var working: Set<String>? = null
        while (scope.isActive) {
            try {
                val (rows, _) = LiveParse.chats(client.call("chats", JSONObject()))
                val before = working
                if (before != null) rows.filter { it.id in before && it.status != "working" && it.status != "waiting" }.forEach { Notes.sessionDone(this, it) }
                working = rows.filter { it.status == "working" }.map { it.id }.toSet()
            } catch (e: Exception) {
                // The wait loop backs off and reconnects; this one simply tries again next round.
            }
            delay(SESSIONS_MS)
        }
    }

    override fun onDestroy() {
        scope.cancel()
    }

    companion object {
        private const val NOTIFICATION = 8
        private const val HIDE = 9
        private const val SESSIONS_MS = 30_000L
        private const val WAIT_S = 45 // under the server's 50 s cap and a typical tunnel's 100 s
        private const val MIN_BACKOFF = 5_000L
        private const val MAX_BACKOFF = 60_000L

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenService::class.java))
        }
    }
}

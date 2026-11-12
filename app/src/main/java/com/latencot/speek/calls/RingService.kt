package com.latencot.speek.calls

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.latencot.speek.R
import com.latencot.speek.data.Store
import com.latencot.speek.mcp.McpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Rings like an alarm: the melody on a loop at alarm volume, vibration, and a full-screen call
 * over the lock screen. It never stops by itself: a volume key, the power button, or Dismiss /
 * Open chat on the call stops it, and that stops it on your other phones too (the server's
 * `call_answered`); another phone stopping it stops it here ([stopAnswered]).
 */
class RingService : Service() {

    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null
    private var wake: PowerManager.WakeLock? = null
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }

    /** The power button turns the screen off: that answers the call, as on an alarm. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        val call = Call(
            key = intent?.getStringExtra("key") ?: return START_NOT_STICKY,
            text = intent.getStringExtra("text").orEmpty(),
            session = intent.getStringExtra("session"),
            ringAt = intent.getStringExtra("ringAt"),
            id = intent.getStringExtra("id"),
        )
        Calls.channels(this)
        ServiceCompat.startForeground(this, NOTIFICATION, notification(call), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        _ringing.value = call
        if (player == null) ring()
        return START_NOT_STICKY
    }

    private fun ring() {
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "speek:ring").apply { acquire(60 * 60 * 1000L) }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audio = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
            .also(audio::requestAudioFocus)
        player = MediaPlayer().apply {
            setAudioAttributes(attrs)
            setDataSource(this@RingService, Uri.parse("android.resource://$packageName/${R.raw.call_melody}"))
            isLooping = true
            setWakeMode(this@RingService, PowerManager.PARTIAL_WAKE_LOCK)
            prepare()
            start()
        }
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 900, 700), 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
        )
        // A playing session with its own volume control gets the volume keys, even with the
        // screen off and the phone locked: any press stops the ring.
        session = MediaSession(this, "speek-call").apply {
            setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 10, 5) {
                override fun onAdjustVolume(direction: Int) = finish()
                override fun onSetVolumeTo(volume: Int) = finish()
            })
            setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            isActive = true
        }
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun notification(call: Call) = NotificationCompat.Builder(this, Calls.CH_CALLS)
        .setSmallIcon(R.drawable.ic_stat_speek)
        .setContentTitle("${Store(this).serverName} is calling")
        .setContentText(call.text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(call.text))
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOngoing(true)
        .setFullScreenIntent(screen(), true)
        .setContentIntent(screen())
        .addAction(0, "Dismiss", PendingIntent.getService(this, 1, Intent(this, RingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun screen() = PendingIntent.getActivity(
        this, 0, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** onDestroy clears [ringing]: it needs the call to tell the other phones it was stopped. */
    private fun finish() = stopSelf()

    override fun onDestroy() {
        val stopped = _ringing.value
        if (stopped?.id != null && !elsewhere) report(stopped.id)
        elsewhere = false
        _ringing.value = null
        if (session != null) unregisterReceiver(screenOff)
        player?.run { stop(); release() }
        player = null
        session?.release()
        vibrator.cancel()
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        wake?.takeIf { it.isHeld }?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val NOTIFICATION = 7
        private const val ACTION_STOP = "stop"
        private val _ringing = MutableStateFlow<Call?>(null)
        /** Set when another phone stopped this call: nothing to report back. */
        @Volatile private var elsewhere = false
        /** Outlives the service: the report goes out after the ring has stopped. */
        private val reports = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The call ringing now; null when quiet. The call screen closes when it clears. */
        val ringing: StateFlow<Call?> = _ringing.asStateFlow()

        fun start(context: Context, call: Call) = ContextCompat.startForegroundService(
            context,
            Intent(context, RingService::class.java)
                .putExtra("key", call.key).putExtra("text", call.text)
                .putExtra("session", call.session).putExtra("ringAt", call.ringAt).putExtra("id", call.id),
        )

        fun stop(context: Context) {
            context.stopService(Intent(context, RingService::class.java))
        }

        /** Another phone stopped one of [ids]: stop here if that call is the one ringing. */
        fun stopAnswered(context: Context, ids: Set<String>) {
            val now = _ringing.value ?: return
            if (now.id !in ids) return
            elsewhere = true
            stop(context)
        }

        /** Tells the server this phone stopped call [id], so the other phones stop it too. */
        private fun Service.report(id: String) {
            val conn = Store(this).connection ?: return
            reports.launch {
                val client = McpClient(conn.endpoint, conn.token)
                repeat(REPORT_TRIES) { attempt ->
                    try {
                        client.call("call_answered", JSONObject().put("id", id))
                        return@launch
                    } catch (e: Exception) {
                        delay(REPORT_BACKOFF_MS * (attempt + 1))
                    }
                }
            }
        }

        private const val REPORT_TRIES = 5
        private const val REPORT_BACKOFF_MS = 3_000L
    }
}

package com.latencot.speek.calls

import com.latencot.speek.data.Store
import androidx.compose.runtime.Composable
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.latencot.speek.MainActivity
import com.latencot.speek.ui.Palette
import com.latencot.speek.ui.SpeekTheme

/** The full-screen call, shown over the lock screen while [RingService] rings. */
class CallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            SpeekTheme {
                val call by RingService.ringing.collectAsStateWithLifecycle()
                val c = call
                LaunchedEffect(c == null) { if (c == null) finish() }
                if (c != null) CallScreen(c)
            }
        }
    }

    @Composable
    private fun CallScreen(call: Call) {
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 1f, targetValue = 1.25f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scale",
        )
        Column(
            Modifier.fillMaxSize().background(Palette.ink),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(104.dp).scale(pulse).background(Palette.blue.copy(alpha = 0.3f), CircleShape))
                    Box(Modifier.size(104.dp).background(Palette.blue, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                    }
                }
                Text("${Store(this@CallActivity).serverName} is calling", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                call.session?.let { Text("Session #$it", color = Color(0xFF94A3B8), fontSize = 15.sp) }
                Text(call.text, color = Color.White, fontSize = 20.sp, lineHeight = 28.sp, textAlign = TextAlign.Center)
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Any volume key or the power button also stops it.",
                    color = Color(0xFF94A3B8), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { RingService.stop(this@CallActivity) },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.red),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).height(64.dp),
                    ) {
                        Icon(Icons.Filled.AlarmOff, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Dismiss", fontSize = 17.sp)
                    }
                    Button(
                        onClick = { openChat(call) },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.blue),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).height(64.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Reply", fontSize = 17.sp)
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    /**
     * Opens Speek on this call's notification with the reply box up (a session's call opens that
     * session), then stops the ring. The ring stops last: stopping it closes this screen, and
     * once this screen is closed Android can no longer dismiss the keyguard for it, so Speek
     * would never open.
     */
    private fun openChat(call: Call) {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply {
                when {
                    call.id != null -> putExtra(Calls.EXTRA_NOTE, call.id).putExtra(Calls.EXTRA_FOCUS, true)
                    call.session != null -> putExtra(Calls.EXTRA_CHAT, call.session)
                    else -> putExtra(Calls.EXTRA_BELL, true)  // a call without an id: at least the list
                }
            }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            startActivity(open)
            RingService.stop(this)
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                startActivity(open)
                RingService.stop(this@CallActivity)
            }
            // Not dismissed (cancelled, or not possible): open anyway; Speek shows over the lock screen's unlock prompt.
            override fun onDismissError() {
                startActivity(open)
                RingService.stop(this@CallActivity)
            }
            override fun onDismissCancelled() = Unit  // the user backed out of unlocking: keep ringing so they can try again
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            RingService.stop(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

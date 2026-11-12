package com.latencot.speek.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.latencot.speek.calls.Calls

/** Calls: the listener switch, what Android must allow for a call to ring, and a test call. */
@Composable
fun CallSettings(listen: Boolean, onListen: (Boolean) -> Unit, onTest: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Calls", fontWeight = FontWeight.SemiBold)
        Text(
            "Your agents can ring this phone like an alarm: a melody and a full-screen call, even locked, until you press a volume key, the power button or Dismiss.",
            fontSize = 13.sp,
            color = Palette.muted,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Listen for calls")
                Text("Stays connected in the background (a quiet notification shows it).", fontSize = 12.sp, color = Palette.muted)
            }
            Switch(checked = listen, onCheckedChange = onListen)
        }
        if (listen) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Hide the listening notification", fontSize = 14.sp)
                    Text(
                        "Android needs it while Speek listens; switch off its category and it stays hidden. Calls still ring.",
                        fontSize = 12.sp,
                        color = Palette.muted,
                    )
                }
                TextButton(onClick = {
                    Calls.channels(context)
                    context.startActivity(Calls.channelSettings(context, Calls.CH_LISTEN))
                }) { Text("Hide") }
            }
        }
        key(tick) {
            Need(
                "Notifications",
                granted = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            ) { notify.launch(Manifest.permission.POST_NOTIFICATIONS) }
            Need(
                "Show calls over the lock screen",
                granted = Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
            ) { context.open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) }
            Need(
                "Run in the background without limits",
                granted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            ) {
                @Suppress("BatteryLife")
                context.open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            }
            Need("Install Speek updates sent in chats", granted = context.packageManager.canRequestPackageInstalls()) {
                context.open(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            }
        }
        OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) { Text("Test call in 10 seconds") }
    }
}

@Composable
private fun key(tick: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(tick) { content() }

@Composable
private fun Need(title: String, granted: Boolean, fix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (granted) {
            Text("Allowed", fontSize = 13.sp, color = Palette.muted)
        } else {
            TextButton(onClick = fix) { Text("Allow") }
        }
    }
}

private fun Context.open(action: String) =
    startActivity(Intent(action, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

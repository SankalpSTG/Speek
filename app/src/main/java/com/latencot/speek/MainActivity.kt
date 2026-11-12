package com.latencot.speek

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.latencot.speek.calls.Calls
import com.latencot.speek.calls.Notes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.latencot.speek.ui.ChatListScreen
import com.latencot.speek.data.AnonSession
import com.latencot.speek.ui.FileAccess
import com.latencot.speek.ui.LiveChatScreen
import com.latencot.speek.ui.NoteFrame
import com.latencot.speek.ui.NotificationThreadScreen
import com.latencot.speek.ui.NotificationsScreen
import com.latencot.speek.ui.SettingsScreen
import com.latencot.speek.ui.SpeekTheme
import com.latencot.speek.ui.SpeekViewModel
import com.latencot.speek.ui.StatusSheet
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val vm: SpeekViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openFrom(intent)
        enableEdgeToEdge()
        setContent {
            SpeekTheme {
                val ui by vm.ui.collectAsStateWithLifecycle()
                val listening by vm.listener.listening.collectAsStateWithLifecycle()
                var settings by rememberSaveable { mutableStateOf(false) }
                val lifecycle = LocalLifecycleOwner.current.lifecycle

                // Notifications are how calls ring: ask once, when connected.
                val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                // Poll the agent's feed and sessions while Speek is on screen.
                LaunchedEffect(ui.connected) {
                    if (!ui.connected) return@LaunchedEffect
                    vm.ensureListening()
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (true) {
                            vm.refresh()
                            vm.loadLive()
                            delay(POLL_MS)
                        }
                    }
                }

                val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) vm.listen()
                }
                val onMic = {
                    when {
                        listening -> vm.listener.stop()
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED -> vm.listen()
                        else -> mic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                when {
                    !ui.connected -> SettingsScreen(vm, onBack = null)
                    settings -> {
                        BackHandler { settings = false }
                        SettingsScreen(vm, onBack = { settings = false })
                    }
                    ui.note != null -> {
                        BackHandler { vm.closeNote() }
                        vm.notes()?.let { api ->
                            NoteFrame("Notification", onBack = vm::closeNote) {
                                NotificationThreadScreen(
                                    api, ui.note!!, ui.noteFocus,
                                    FileAccess(ui.fetched, ui.fetching, vm::local, vm::fetch, vm::openFile),
                                )
                            }
                        }
                    }
                    ui.bell -> {
                        BackHandler { vm.closeBell() }
                        vm.notes()?.let { api ->
                            NoteFrame("Notifications", onBack = vm::closeBell) {
                                NotificationsScreen(api, onOpen = { vm.openNote(it) })
                            }
                        }
                    }
                    ui.open != null -> {
                        BackHandler { vm.close() }
                        // Only Claude Code ("cc:") and anonymous chats open.
                        LiveChatScreen(vm, onMic = onMic)
                    }
                    else -> ChatListScreen(vm, onSettings = { settings = true })
                }
                if (ui.statusOpen) {
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(STATUS_MS)
                            vm.refreshStatus()
                        }
                    }
                    StatusSheet(ui.dashboard, agents = ui.agents, onAgent = vm::setAgent, onWorkspace = vm::setWorkspace, onClose = vm::closeStatus)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Notes.visible = true
        Notes.clearAll(this)
    }

    override fun onPause() {
        super.onPause()
        Notes.visible = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** A call, a missed call or a notification opens its notification (or its chat). */
    private fun openFrom(intent: Intent) {
        intent.getStringExtra(Calls.EXTRA_NOTE)?.let { id ->
            val focus = intent.getBooleanExtra(Calls.EXTRA_FOCUS, false)
            intent.removeExtra(Calls.EXTRA_NOTE)
            intent.removeExtra(Calls.EXTRA_FOCUS)
            vm.openBell()
            vm.openNote(id, focus)
            return
        }
        if (intent.getBooleanExtra(Calls.EXTRA_BELL, false)) {
            intent.removeExtra(Calls.EXTRA_BELL)
            vm.openBell()
            return
        }
        if (!intent.hasExtra(Calls.EXTRA_CHAT)) return
        val chat = intent.getStringExtra(Calls.EXTRA_CHAT)
        intent.removeExtra(Calls.EXTRA_CHAT)
        // A session's notification opens its chat; anything else is a notification (the bell).
        when {
            chat == null -> vm.close()
            chat.startsWith("cc:") || chat.startsWith(AnonSession.PREFIX) -> vm.open(chat)
            else -> vm.openBell()
        }
    }

    companion object {
        const val POLL_MS = 8_000L
        const val STATUS_MS = 10_000L
    }
}

package com.latencot.speek.ui

import com.latencot.speek.data.Line
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.latencot.speek.notes.NotificationsApi
import com.latencot.speek.notes.Thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One notification and its thread. A reply goes to the headless run that sent it (resumed with
 * everything it did, or straight to it while it waits on you); its answer appears here.
 * focusReply: opened from a call's "Chat about this", so the keyboard is up at once.
 */
@Composable
fun NotificationThreadScreen(api: NotificationsApi, id: String, focusReply: Boolean, access: FileAccess) {
    var thread by remember { mutableStateOf<Thread?>(null) }
    var draft by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(id) {
        while (true) {
            try {
                thread = api.thread(id)
            } catch (e: Exception) {
                note = e.message
            }
            // Quick while its run is answering, slower once it is idle.
            delay(if (thread?.status == "working") 2_000 else 6_000)
        }
    }
    LaunchedEffect(thread?.entries?.size) {
        thread?.let { if (it.entries.isNotEmpty()) list.animateScrollToItem(it.entries.size) }
    }
    LaunchedEffect(focusReply) { if (focusReply) focus.requestFocus() }

    val t = thread
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t != null) {
                item("head") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.notification.from, style = MaterialTheme.typography.titleSmall,
                                color = importanceColor(t.notification.importance))
                            Text("${t.notification.kind} · ${t.notification.created.substring(5, 16).replace('T', ' ')}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        }
                        Text(t.notification.text, style = MaterialTheme.typography.bodyMedium)
                        // Its files (a new Speek build, a screenshot): tap fetches, tap again opens / installs.
                        if (t.notification.files.isNotEmpty()) {
                            Attachments(
                                Line(
                                    key = t.notification.id, at = t.notification.created.replace('T', ' '), fromMe = false,
                                    kind = t.notification.kind, text = t.notification.text, session = null,
                                    files = t.notification.files, id = t.notification.id,
                                ),
                                access,
                            )
                        }
                        if (t.notification.buttons.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                t.notification.buttons.forEach { (label, data) ->
                                    OutlinedButton(onClick = {
                                        scope.launch { note = runCatching { api.press(data) }.getOrElse { it.message } }
                                    }) { Text(label) }
                                }
                            }
                        }
                    }
                }
                items(t.entries, key = { "${it.at}|${it.text.hashCode()}" }) { e -> Bubble(e.fromMe, e.text, t.notification.from) }
                if (t.status == "working") {
                    item("working") {
                        Text("${t.notification.from} is answering…", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
            }
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text("Reply to ${t?.notification?.from ?: "it"}") },
            )
            Button(enabled = draft.isNotBlank() && !sending, onClick = {
                val text = draft.trim()
                sending = true
                scope.launch {
                    note = runCatching { api.reply(id, text) }.getOrElse { it.message }
                    if (note?.startsWith("Sent") == true) draft = ""
                    sending = false
                    thread = runCatching { api.thread(id) }.getOrNull() ?: thread
                }
            }) { Text("Send") }
        }
    }
}

@Composable
private fun Bubble(fromMe: Boolean, text: String, from: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = if (fromMe) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.widthIn(max = 320.dp)
                .background(
                    if (fromMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!fromMe) Text(from, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

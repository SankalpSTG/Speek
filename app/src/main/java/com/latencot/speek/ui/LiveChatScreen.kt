package com.latencot.speek.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.latencot.speek.data.Ask
import com.latencot.speek.data.LiveEvent
import com.latencot.speek.data.LiveParse

/** What the chat shows: transcript events (tool calls merged with their results) and permission requests. */
private sealed interface Item {
    val key: String
    val at: String

    data class Mine(override val key: String, override val at: String, val text: String) : Item
    data class Reply(override val key: String, override val at: String, val text: String) : Item
    data class Note(override val key: String, override val at: String, val text: String) : Item
    data class Step(override val key: String, override val at: String, val name: String, val summary: String, val result: LiveEvent?) : Item
    data class Request(override val key: String, override val at: String, val ask: Ask) : Item
}

private fun items(events: List<LiveEvent>, asks: List<Ask>): List<Item> {
    val results = events.filter { it.type == "result" }.associateBy { it.toolId }
    val shown = events.mapNotNull { e ->
        when (e.type) {
            "user" -> Item.Mine("e${e.seq}", e.at, LiveParse.shown(e.text))
            "text" -> Item.Reply("e${e.seq}", e.at, e.text)
            "note" -> Item.Note("e${e.seq}", e.at, e.text)
            "tool" -> Item.Step("e${e.seq}", e.at, e.name, e.summary, results[e.toolId])
            else -> null
        }
    }
    // A request sits where it was made: after the step that asked (same second sorts after it).
    return (shown + asks.map { Item.Request("a${it.rid}", it.at, it) }).sortedBy { it.at }
}

/**
 * A live chat, step by step: a Claude Code session on the server's machine ("cc:ID"), or an
 * anonymous chat ("fp:N", a background session the server runs).
 */
@Composable
fun LiveChatScreen(vm: SpeekViewModel, onMic: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listening by vm.listener.listening.collectAsStateWithLifecycle()
    val heard by vm.listener.heard.collectAsStateWithLifecycle()
    val level by vm.listener.level.collectAsStateWithLifecycle()
    val ref = ui.open ?: return
    val session = ui.openLive
    val anon = ui.openAnon
    val isAnon = ui.openN != null
    val name = session?.name ?: anon?.name ?: ref
    val status = ui.liveStatus
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val shown = items(ui.liveEvents, ui.liveAsks)
    val list = rememberLazyListState()
    var shot by remember { mutableStateOf<Uri?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { vm.attach(it) }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.attach(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        shot?.let { if (ok) vm.attach(listOf(it)) }
        shot = null
    }

    LaunchedEffect(ref, shown.size, status) {
        if (shown.isNotEmpty()) list.animateScrollToItem(shown.size + 1)
    }

    Column(Modifier.fillMaxSize().background(Palette.wall)) {
        Column(Modifier.background(Color.White)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = vm::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Chats") }
                Avatar(ref, name, size = 38)
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            status == "working" -> "working..."
                            status == "waiting" -> "needs you"
                            status == "stopped" -> "stopped · your next message resumes it"
                            status == "failed" -> "the last turn failed · send anything to retry"
                            session?.inTerminal == true -> "in a terminal on the server's machine"
                            isAnon -> "anonymous · a background session on the server"
                            else -> session?.cwd?.replace(Regex("^/(?:home|Users)/[^/]+"), "~") ?: ""
                        },
                        fontSize = 12.sp,
                        color = when (status) {
                            "working" -> Palette.blue
                            "waiting", "failed" -> Palette.amber
                            else -> Palette.muted
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!isAnon && (status == "working" || status == "waiting") && session?.inTerminal != true) {
                    IconButton(onClick = vm::interrupt) {
                        Icon(Icons.Filled.StopCircle, contentDescription = "Stop", tint = Palette.red)
                    }
                }
                if (session?.inTerminal != true) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color.White) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text("Delete", color = Palette.red) }, onClick = { menu = false; deleting = true })
                        }
                    }
                }
            }
            HorizontalDivider(color = Palette.line)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty() && status == "idle") {
                Text(
                    "Loading the session...",
                    color = Palette.muted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 36.dp)
                        .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(shown, key = { it.key }) { item ->
                    when (item) {
                        is Item.Mine -> TextBubble(item.text, item.at, mine = true)
                        is Item.Reply -> TextBubble(item.text, item.at, mine = false)
                        is Item.Note -> NoteRow(item.text)
                        is Item.Step -> StepRow(item)
                        is Item.Request -> AskCard(item.ask, onAnswer = vm::approve)
                    }
                }
                item {
                    if (status == "working") Working() else Spacer(Modifier.height(4.dp))
                }
            }
        }

        ui.notice?.let { Notice(it, vm::dismissNotice) }
        if (session?.inTerminal == true) {
            Text(
                "This session runs in a terminal on the server's machine. Approvals work here; reply there.",
                fontSize = 13.sp,
                color = Palette.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 14.dp),
            )
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            return@Column
        }
        if (ui.pending.isNotEmpty()) {
            Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) { PendingStrip(ui.pending, vm::unattach) }
        }
        Composer(
            draft = ui.draft,
            hasFiles = ui.pending.isNotEmpty(),
            sending = ui.sending,
            listening = listening,
            canTalk = vm.listener.available,
            heard = heard,
            level = level,
            onDraft = vm::setDraft,
            onSend = vm::send,
            onMic = onMic,
            onGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onCamera = {
                val (_, uri) = vm.files.cameraTarget()
                shot = uri
                camera.launch(uri)
            },
            onFiles = { documents.launch(arrayOf("*/*")) },
        )
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars.union(WindowInsets.ime)))
    }

    if (renaming) RenameDialog(name, onRename = { renaming = false; vm.rename(ref, it) }, onClose = { renaming = false })
    if (deleting) DeleteDialog(name, anonymous = isAnon, onDelete = { deleting = false; vm.delete(ref) }, onClose = { deleting = false })
}

/** Something else that went into an anonymous chat's turn (a task finished, a notification you discussed). */
@Composable
private fun NoteRow(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = 12.sp,
            color = Palette.muted,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun TextBubble(text: String, at: String, mine: Boolean) {
    val shape = if (mine) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp) else RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .shadow(1.dp, shape)
                .background(if (mine) Palette.blue else Color.White, shape)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SelectionContainer {
                if (mine) {
                    Text(text, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp)
                } else {
                    MarkdownText(text, color = Palette.ink)
                }
            }
            Text(
                bubbleTime(at),
                fontSize = 11.sp,
                color = if (mine) Color(0xCCFFFFFF) else Palette.muted,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

private fun iconFor(tool: String): ImageVector = when (tool) {
    "Bash" -> Icons.Filled.Terminal
    "Read" -> Icons.Filled.Description
    "Edit", "Write", "NotebookEdit" -> Icons.Filled.EditNote
    "Grep", "Glob" -> Icons.Filled.Search
    "WebFetch", "WebSearch" -> Icons.Filled.Language
    else -> Icons.Filled.Build
}

/** One tool step: what it touched, and (tap) what came back. */
@Composable
private fun StepRow(step: Item.Step) {
    var open by rememberSaveable(step.key) { mutableStateOf(false) }
    val r = step.result
    Column(
        modifier = Modifier
            .widthIn(max = 340.dp)
            .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .clickable(enabled = r != null) { open = !open }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(iconFor(step.name), contentDescription = null, tint = Palette.muted, modifier = Modifier.size(16.dp))
            Text(step.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
            Text(
                step.summary.replace(Regex("/(?:home|Users)/[^/]+"), "~"),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Palette.muted,
                maxLines = if (open) 6 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            when {
                r == null -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Palette.blue)
                r.error -> Icon(Icons.Filled.Close, contentDescription = "Failed", tint = Palette.red, modifier = Modifier.size(14.dp))
                else -> Icon(Icons.Filled.Check, contentDescription = "Done", tint = Color(0xFF16A34A), modifier = Modifier.size(14.dp))
            }
        }
        if (open && r != null && r.preview.isNotBlank()) {
            SelectionContainer {
                Text(
                    r.preview,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.ink,
                    maxLines = 14,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A permission request: what it wants to do, and your Deny / Allow / Always. */
@Composable
private fun AskCard(a: Ask, onAnswer: (String, String) -> Unit) {
    val pending = a.status == "pending"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(14.dp))
            .background(Color.White, RoundedCornerShape(14.dp))
            .border(1.5.dp, if (pending) Palette.amber else Palette.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(iconFor(a.tool), contentDescription = null, tint = Palette.amber, modifier = Modifier.size(18.dp))
            Text("Wants to use ${a.tool}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .background(Palette.wall, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            SelectionContainer {
                Text(a.detail.replace(Regex("/(?:home|Users)/[^/]+"), "~"), fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
            }
        }
        if (pending) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onAnswer(a.rid, "deny") }, modifier = Modifier.weight(1f)) {
                    Text("Deny", color = Palette.red)
                }
                Button(
                    onClick = { onAnswer(a.rid, "allow") },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.blue),
                    modifier = Modifier.weight(1f),
                ) { Text("Allow") }
                TextButton(onClick = { onAnswer(a.rid, "always") }) { Text("Always") }
            }
        } else {
            Text(
                a.status.replaceFirstChar { it.uppercase() },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = when (a.status) {
                    "allowed" -> Color(0xFF16A34A)
                    "denied" -> Palette.red
                    else -> Palette.muted
                },
            )
        }
    }
}

@Composable
private fun Working() {
    Row(
        modifier = Modifier
            .background(Color.White, RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Palette.blue)
        Text("Working...", fontSize = 13.sp, color = Palette.muted)
    }
}

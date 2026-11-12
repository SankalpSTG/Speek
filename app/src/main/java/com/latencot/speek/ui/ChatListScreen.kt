package com.latencot.speek.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.latencot.speek.data.AnonSession
import com.latencot.speek.data.Chat

@Composable
fun ChatListScreen(vm: SpeekViewModel, onSettings: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf<Chat?>(null) }
    var renaming by remember { mutableStateOf<Chat?>(null) }
    var deleting by remember { mutableStateOf<Chat?>(null) }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Speek",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp),
                )
                IconButton(onClick = vm::openBell) {
                    BadgedBox(badge = { if (ui.bellUnread > 0) Badge { Text(if (ui.bellUnread > 99) "99+" else "${ui.bellUnread}") } }) {
                        Icon(Icons.Filled.Notifications, contentDescription = "Notifications")
                    }
                }
                if (ui.hasStatus) IconButton(onClick = vm::loadStatus) { Icon(Icons.Filled.Insights, contentDescription = "Background") }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            }
            if (ui.hasAnon) TabRow(
                selectedTabIndex = if (ui.anonTab) 1 else 0,
                containerColor = Color.White,
                contentColor = Palette.blue,
                divider = { HorizontalDivider(color = Palette.line) },
            ) {
                Tab(selected = !ui.anonTab, onClick = { vm.showAnon(false) }, text = { TabLabel("Chats", ui.chats.count { it.unread > 0 }) })
                Tab(selected = ui.anonTab, onClick = { vm.showAnon(true) }, text = { TabLabel("Anonymous", ui.anonChats.count { it.unread > 0 }) })
            }
            ui.notice?.let { Notice(it, vm::dismissNotice) }
            val chats = if (ui.anonTab) ui.anonChats else ui.chats
            if (chats.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (ui.anonTab) "No anonymous chats yet. Start one below." else "No chats yet. Start one with New chat.",
                        color = Palette.muted,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(chats, key = { it.ref }) { chat ->
                        ChatRow(
                            chat,
                            onClick = { vm.open(chat.ref) },
                            // Sessions open in a terminal are not renamed or deleted from here.
                            onLongClick = if (ui.live.any { it.ref == chat.ref && it.inTerminal }) null else ({ acting = chat }),
                        )
                    }
                    item { Spacer(Modifier.size(96.dp)) }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd)) {
            ExtendedFloatingActionButton(
                onClick = { naming = true },
                icon = { Icon(Icons.Outlined.Chat, contentDescription = null) },
                text = { Text(if (ui.anonTab) "New anonymous chat" else "New chat") },
                containerColor = Palette.blue,
                contentColor = Color.White,
                modifier = Modifier.padding(20.dp),
            )
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    if (naming) {
        val anon = ui.anonTab
        NewChatSheet(
            anonymous = anon,
            onStart = { name, text -> naming = false; if (anon) vm.newAnon(name, text) else vm.newChat(name, text) },
            onClose = { naming = false },
        )
    }
    acting?.let { chat ->
        ActionsSheet(
            chat.name,
            onRename = { acting = null; renaming = chat },
            onDelete = { acting = null; deleting = chat },
            onClose = { acting = null },
        )
    }
    renaming?.let { chat -> RenameDialog(chat.name, onRename = { renaming = null; vm.rename(chat.ref, it) }, onClose = { renaming = null }) }
    deleting?.let { chat ->
        DeleteDialog(chat.name, anonymous = !chat.ref.startsWith("cc:"), onDelete = { deleting = null; vm.delete(chat.ref) }, onClose = { deleting = null })
    }
}

@Composable
private fun TabLabel(text: String, unread: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, fontWeight = FontWeight.SemiBold)
        if (unread > 0) Box(Modifier.size(8.dp).background(Palette.blue, CircleShape))
    }
}

/** Long-press on a chat: rename it or delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionsSheet(name: String, onRename: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SheetAction(Icons.Filled.Edit, "Rename", Palette.ink, onRename)
            SheetAction(Icons.Filled.Delete, "Delete", Palette.red, onDelete)
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, fontSize = 16.sp, color = color)
    }
}

/**
 * New chat: a Claude Code session on the server's machine, or [anonymous]: a background session
 * the server runs (an extension). Either starts with your first message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(anonymous: Boolean, onStart: (String, String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Color.White) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (anonymous) "New anonymous chat" else "New chat", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (anonymous) "A background session that the server runs. It does not show in claude agents."
                else "A Claude Code session on the server's machine. It shows up in claude agents too.",
                fontSize = 13.sp,
                color = Palette.muted,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(if (anonymous) "Name (optional)" else "Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("First message") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { onStart(name, text) },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.blue),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start") }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(chat: Chat, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(chat.ref, chat.name)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    chat.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    listTime(chat.at),
                    fontSize = 12.sp,
                    color = if (chat.unread > 0) Palette.blue else Palette.muted,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        chat.working -> "working..."
                        chat.previewFromMe -> "You: ${chat.preview}"
                        else -> chat.preview
                    },
                    fontSize = 14.sp,
                    fontWeight = if (chat.waiting) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        chat.waiting -> Palette.amber
                        chat.working -> Palette.blue
                        else -> Palette.muted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.unread > 0 && chat.live) {
                    Box(Modifier.size(12.dp).background(if (chat.waiting) Palette.amber else Palette.blue, CircleShape))
                } else if (chat.unread > 0) {
                    Box(
                        Modifier.widthIn(min = 22.dp).background(Palette.blue, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${chat.unread}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private val avatarColors = listOf(
    Color(0xFF2563EB), Color(0xFF0891B2), Color(0xFF7C3AED), Color(0xFF059669),
    Color(0xFFDB2777), Color(0xFFD97706), Color(0xFF4F46E5),
)

@Composable
fun Avatar(ref: String, name: String, size: Int = 48) {
    Box(
        Modifier.size(size.dp).background(
            when {
                ref.startsWith(AnonSession.PREFIX) -> Palette.muted // anonymous chats: one quiet colour
                else -> avatarColors[Math.floorMod(ref.hashCode(), avatarColors.size)]
            },
            CircleShape,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (name.firstOrNull { it.isLetterOrDigit() } ?: '#').uppercase(),
            color = Color.White,
            fontSize = (size * 0.4).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun Notice(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Palette.blueSoft).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
    }
}

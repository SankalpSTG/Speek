package com.latencot.speek.ui

import com.latencot.speek.data.Media
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.latencot.speek.notes.Notification
import com.latencot.speek.notes.NotificationsApi
import kotlinx.coroutines.delay

/**
 * The bell: every notification, newest first. No message box: a notification is answered by
 * opening it and replying there, which reaches the run that sent it.
 */
@Composable
fun NotificationsScreen(api: NotificationsApi, onOpen: (String) -> Unit) {
    var items by remember { mutableStateOf<List<Notification>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                items = api.list()
                error = null
            } catch (e: Exception) {
                error = e.message
            }
            delay(5_000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { n ->
                NotificationRow(n) { onOpen(n.id) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun NotificationRow(n: Notification, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(vertical = 6.dp)) {
            Box(Modifier.size(10.dp).background(importanceColor(n.importance), CircleShape))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(n.from, style = MaterialTheme.typography.labelLarge)
                Text(n.kind, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Box(Modifier.weight(1f))
                Text(n.created.substring(5, 16).replace('T', ' '), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline)
            }
            Text(n.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (n.files.isNotEmpty()) {
                Text(
                    if (n.files.size == 1) "Attachment: ${Media.name(n.files[0])}" else "${n.files.size} attachments",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                )
            }
            if (n.replies > 0 || n.threadStatus == "working") {
                Text(
                    when (n.threadStatus) {
                        "working" -> "${n.from} is answering…"
                        "failed" -> "${n.replies} in thread · last answer failed"
                        else -> "${n.replies} in thread"
                    },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

fun importanceColor(level: String): Color = when (level) {
    "urgent" -> Color(0xFFDC2626)
    "normal" -> Color(0xFF2563EB)
    else -> Color(0xFF9CA3AF)
}

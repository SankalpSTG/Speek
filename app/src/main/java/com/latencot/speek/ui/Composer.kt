package com.latencot.speek.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * WhatsApp's bottom bar: a rounded "Message" field with attach and camera inside it, and one
 * round button that is the mic while the field is empty and Send once there is something to
 * send. While you talk the field shows a pulsing dot and your words as they are heard.
 */
@Composable
fun Composer(
    draft: String,
    hasFiles: Boolean,
    sending: Boolean,
    listening: Boolean,
    /** The phone can turn speech into text: without it the round button is Send only. */
    canTalk: Boolean,
    heard: String,
    level: Float,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onFiles: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val canSend = draft.isNotBlank() || hasFiles
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .shadow(1.dp, RoundedCornerShape(24.dp))
                .background(Color.White, RoundedCornerShape(24.dp))
                .padding(horizontal = 4.dp, vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (listening) {
                val blink by rememberInfiniteTransition(label = "rec").animateFloat(
                    1f, 0.2f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot",
                )
                Box(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
                    Box(Modifier.size(10.dp).alpha(blink).background(Palette.red, CircleShape))
                }
                Text(
                    heard.ifEmpty { "Listening..." },
                    fontSize = 16.sp,
                    color = if (heard.isEmpty()) Palette.muted else Palette.ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 0.dp, vertical = 12.dp),
                )
            } else {
                Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp)) {
                    if (draft.isEmpty()) Text("Message", fontSize = 16.sp, color = Palette.muted)
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraft,
                        textStyle = TextStyle(fontSize = 16.sp, color = Palette.ink, lineHeight = 22.sp),
                        cursorBrush = SolidColor(Palette.blue),
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !sending) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach", tint = Palette.muted, modifier = Modifier.rotate(-45f))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color.White) {
                        DropdownMenuItem(
                            text = { Text("Photos and videos") },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = Palette.blue) },
                            onClick = { menu = false; onGallery() },
                        )
                        DropdownMenuItem(
                            text = { Text("Files") },
                            leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = Palette.blue) },
                            onClick = { menu = false; onFiles() },
                        )
                    }
                }
                if (draft.isEmpty()) {
                    IconButton(onClick = onCamera, enabled = !sending) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = "Camera", tint = Palette.muted)
                    }
                }
            }
        }
        RoundAction(
            icon = when {
                listening -> Icons.Filled.Stop
                canSend || !canTalk -> Icons.AutoMirrored.Filled.Send
                else -> Icons.Filled.Mic
            },
            description = when {
                listening -> "Stop listening"
                canSend || !canTalk -> "Send"
                else -> "Talk"
            },
            busy = sending,
            enabled = listening || canSend || canTalk,
            halo = if (listening) level else null,
            onClick = if (canSend && !listening) onSend else onMic,
        )
    }
}

@Composable
private fun RoundAction(
    icon: ImageVector,
    description: String,
    busy: Boolean,
    enabled: Boolean,
    halo: Float?,
    onClick: () -> Unit,
) {
    val grow by animateFloatAsState(if (halo != null) 1f + halo * 0.5f else 1f, label = "halo")
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (halo != null) Box(Modifier.size(48.dp).scale(grow).background(Palette.blueSoft, CircleShape))
        Box(
            Modifier.size(48.dp).shadow(2.dp, CircleShape).background(Palette.blue, CircleShape)
                .alpha(if (enabled) 1f else 0.4f)
                .clickable(enabled = !busy && enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

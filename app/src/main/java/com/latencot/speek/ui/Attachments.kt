package com.latencot.speek.ui

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.latencot.speek.data.Files
import com.latencot.speek.data.Line
import com.latencot.speek.data.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** How a bubble reaches its files: the view model's fetch state and actions. */
class FileAccess(
    val fetched: Set<String>,
    val fetching: Set<String>,
    val local: (String) -> File,
    val fetch: (String) -> Unit,
    val open: (Line, String) -> Unit,
)

/** A message's files: images as pictures (fetched as soon as they are shown), others as chips. */
@Composable
fun Attachments(line: Line, access: FileAccess) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = if (line.fromMe) Alignment.End else Alignment.Start) {
        line.files.forEach { path ->
            val url = Media.isUrl(path)
            val file: File? = when {
                line.fromMe -> File(path)
                url -> null
                path in access.fetched -> access.local(path)
                else -> null
            }
            if (Media.isImage(path) && !url) {
                if (!line.fromMe) LaunchedEffect(path) { access.fetch(path) }
                val bitmap by produceState<Bitmap?>(null, file) {
                    value = file?.takeIf { it.exists() }?.let { withContext(Dispatchers.IO) { Files.thumbnail(it, 720) } }
                }
                val b = bitmap
                if (b != null) {
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = Media.name(path),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .widthIn(max = 260.dp)
                            .heightIn(max = 320.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { access.open(line, path) },
                    )
                    return@forEach
                }
            }
            FileChip(
                name = Media.name(path),
                icon = iconFor(path),
                busy = path in access.fetching,
                needsFetch = !line.fromMe && !url && file == null,
                mine = line.fromMe,
                onClick = { access.open(line, path) },
            )
        }
    }
}

@Composable
private fun FileChip(name: String, icon: ImageVector, busy: Boolean, needsFetch: Boolean, mine: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .background(if (mine) Palette.blue else Palette.agent, RoundedCornerShape(14.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val tint = if (mine) Color.White else Palette.blue
        Box(Modifier.size(36.dp).background(if (mine) Color(0x33FFFFFF) else Color.White, CircleShape), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = tint)
                needsFetch -> Icon(Icons.Filled.Download, contentDescription = "Download", tint = tint)
                else -> Icon(icon, contentDescription = null, tint = tint)
            }
        }
        Text(
            name,
            color = if (mine) Color.White else Palette.ink,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun iconFor(path: String): ImageVector = when {
    Media.isUrl(path) -> Icons.Filled.Link
    Media.isImage(path) -> Icons.Filled.Image
    Media.isVideo(path) -> Icons.Filled.VideoFile
    Media.isAudio(path) -> Icons.Filled.AudioFile
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** The files picked for the next message, each removable. */
@Composable
fun PendingStrip(pending: List<Files.Pending>, onRemove: (Files.Pending) -> Unit) {
    val context = LocalContext.current
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(pending, key = { it.uri.toString() }) { p ->
            Box {
                val thumb by produceState<Bitmap?>(null, p.uri) {
                    if (Media.isImage(p.name)) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(p.uri)?.use { s ->
                                    BitmapFactory.decodeStream(s, null,
                                        BitmapFactory.Options().apply { inSampleSize = 8 })
                                }
                            }.getOrNull()
                        }
                    }
                }
                val t = thumb
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Palette.agent),
                    contentAlignment = Alignment.Center,
                ) {
                    if (t != null) {
                        Image(t.asImageBitmap(), contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp))
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Icon(iconFor(p.name), contentDescription = null, tint = Palette.blue)
                            Text(p.name, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).size(22.dp).background(Palette.ink, CircleShape).clickable { onRemove(p) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove ${p.name}", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

package com.latencot.speek.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A new name for a chat; the server renames the session itself. */
@Composable
fun RenameDialog(current: String, onRename: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Color.White,
        title = { Text("Rename chat", fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank() && name.trim() != current) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Deleting is for good: the session goes from the server's machine too. */
@Composable
fun DeleteDialog(name: String, anonymous: Boolean, onDelete: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Color.White,
        title = { Text("Delete $name?", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (anonymous) "The background session and its conversation are deleted on the server."
                    else "claude rm on the server's machine: the session and its worktree go. Unpushed work in the worktree stops it.",
                    fontSize = 14.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = Palette.red) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

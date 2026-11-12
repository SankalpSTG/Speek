package com.latencot.speek.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(vm: SpeekViewModel, onBack: (() -> Unit)?) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val canSpeak by vm.speaker.available.collectAsStateWithLifecycle()
    var endpoint by remember { mutableStateOf(ui.endpoint) }
    var token by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
        }
        HorizontalDivider(color = Palette.line)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Agent", fontWeight = FontWeight.SemiBold)
            Text(
                "Speek talks to a Speek server over MCP. Enter its MCP endpoint and bearer token.",
                fontSize = 13.sp,
                color = Palette.muted,
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text("MCP endpoint") },
                placeholder = { Text("https://your-server.example.com/mcp") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(if (ui.connected) "Token (enter again to change)" else "Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.connect(endpoint, token) { onBack?.invoke() } },
                enabled = endpoint.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }
            ui.notice?.let { Text(it, fontSize = 13.sp, color = Palette.muted) }

            // Each voice setting shows only when this phone can do it (speech recognition, text to speech).
            if (vm.listener.available || canSpeak) HorizontalDivider(color = Palette.line)
            if (vm.listener.available) {
                Toggle("Send when I stop talking", "Off: the words wait in the box for you to send.", ui.sendWhenDone, vm::setSendWhenDone)
            }
            if (canSpeak) {
                Toggle("Read answers aloud", "New messages are spoken while Speek is open.", ui.readAloud, vm::setReadAloud)
            }

            HorizontalDivider(color = Palette.line)
            CallSettings(listen = ui.listen, onListen = vm::setListen, onTest = vm::testCall)
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars.union(WindowInsets.ime)))
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title)
            Text(detail, fontSize = 12.sp, color = Palette.muted)
        }
        Switch(checked = on, onCheckedChange = set)
    }
}

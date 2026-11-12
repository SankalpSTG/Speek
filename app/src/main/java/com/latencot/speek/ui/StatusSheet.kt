package com.latencot.speek.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.latencot.speek.data.Agent
import com.latencot.speek.data.Dashboard
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What the server runs in the background, by workspace: what needs you and what runs now on top,
 * then one card per workspace with its switch, its numbers, its agents (each with its own
 * switch) and its recent tasks (tap one for its result). Refreshed while open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusSheet(
    d: Dashboard?,
    agents: List<Agent>,
    onAgent: (String, Boolean) -> Unit,
    onWorkspace: (String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Background", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.ink)
            if (d == null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                if (d.needsYou.isNotEmpty()) Section("Needs you") {
                    d.needsYou.forEach { ItemCard(it, Palette.red, Palette.redSoft) }
                }
                Section("Running now") {
                    if (d.now.isEmpty()) {
                        Text("Nothing is running.", fontSize = 15.sp, color = Palette.muted)
                    } else {
                        d.now.forEach { ItemCard(it, Palette.green, Palette.greenSoft) }
                    }
                }
                Section("Workspaces") {
                    d.workspaces.forEach { ws ->
                        WorkspaceCard(
                            ws,
                            agents = agents.filter { it.workspace == ws.name },
                            tasks = d.tasks.filter { it.workspace == ws.name },
                            tasksHours = d.tasksHours,
                            onWorkspace = onWorkspace,
                            onAgent = onAgent,
                        )
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.muted, letterSpacing = 0.8.sp)
        content()
    }
}

@Composable
private fun Card(bg: Color = Color.White, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .border(1.dp, if (bg == Color.White) Palette.line else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { content() }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(9.dp).background(color, CircleShape))

@Composable
private fun ItemCard(item: Dashboard.Item, accent: Color, bg: Color) = Card(bg) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(vertical = 6.dp)) { Dot(accent) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
            if (item.detail.isNotEmpty()) {
                Text(item.detail, fontSize = 14.sp, lineHeight = 19.sp, color = Palette.ink.copy(alpha = 0.75f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A workspace: its switch flips all its agents at once; it reads on while any agent is on. */
@Composable
private fun WorkspaceCard(
    ws: Dashboard.Space,
    agents: List<Agent>,
    tasks: List<Dashboard.Task>,
    tasksHours: Int,
    onWorkspace: (String, Boolean) -> Unit,
    onAgent: (String, Boolean) -> Unit,
) = Card {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ws.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.ink)
                Text(
                    if (ws.enabled) "${agents.count { it.enabled }} of ${agents.size} agents on" else "All agents off",
                    fontSize = 13.sp,
                    color = if (ws.enabled) Palette.green else Palette.muted,
                )
            }
            Switch(checked = ws.enabled, onCheckedChange = { onWorkspace(ws.name, it) })
        }
        ws.numbers.forEach { g -> Tiles(g.heading.removePrefix(ws.name).trim(), g.tiles) }
        if (agents.isNotEmpty()) {
            HorizontalDivider(color = Palette.line)
            agents.forEach { AgentRow(it, onAgent) }
        }
        if (tasks.isNotEmpty()) {
            HorizontalDivider(color = Palette.line)
            Text("Tasks, last $tasksHours h", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.muted)
            tasks.forEach { TaskRow(it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tiles(heading: String, tiles: List<Dashboard.Tile>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (heading.isNotEmpty()) Text(heading, fontSize = 12.sp, color = Palette.muted)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.forEach { t ->
                Column(
                    Modifier.widthIn(min = 96.dp).background(Palette.agent, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("%,d".format(t.value), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.ink)
                    Text(t.label.replace('-', ' '), fontSize = 12.sp, color = Palette.muted)
                }
            }
        }
    }
}

private fun stateColor(state: String) = when (state) {
    "running" -> Palette.green
    "failed", "crashed", "limited" -> Palette.red
    else -> Palette.muted
}

private val shown = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/** "2026-11-06T18:37:01" -> "6 Nov, 18:37"; "" stays "". */
private fun at(iso: String) = if (iso.isEmpty()) "" else LocalDateTime.parse(iso).format(shown)

/** One agent. On starts it now; off stops it now. */
@Composable
private fun AgentRow(a: Agent, onAgent: (String, Boolean) -> Unit) {
    val runs = a.enabled
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(if (runs) stateColor(a.state) else Palette.line)
                Text(a.id.substringAfter('.'), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (runs) Palette.ink else Palette.muted)
                Text(if (a.enabled) a.state else "off", fontSize = 13.sp, color = stateColor(a.state))
            }
            if (a.summary.isNotEmpty()) {
                Text(a.summary, fontSize = 14.sp, lineHeight = 19.sp, color = Palette.ink.copy(alpha = if (runs) 0.75f else 0.5f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val times = listOfNotNull(at(a.last).takeIf { it.isNotEmpty() }?.let { "last $it" }, a.next.takeIf { it.isNotEmpty() }?.let { "next $it" })
            if (times.isNotEmpty()) Text(times.joinToString("  ·  "), fontSize = 12.sp, color = Palette.muted)
        }
        Switch(checked = a.enabled, onCheckedChange = { onAgent(a.id, it) })
    }
}

/** A delegated task; tapping it shows what it reported. */
@Composable
private fun TaskRow(t: Dashboard.Task) {
    var open by rememberSaveable(t.name, t.`when`) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(enabled = t.result.isNotEmpty()) { open = !open }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(stateColor(t.state))
            Text(t.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink, modifier = Modifier.weight(1f, fill = false))
            Text("${t.state} · ${t.`when`}", fontSize = 13.sp, color = stateColor(t.state))
        }
        Text(t.task, fontSize = 14.sp, lineHeight = 19.sp, color = Palette.ink.copy(alpha = 0.75f), maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        if (open) {
            Spacer(Modifier.height(2.dp))
            Box(Modifier.fillMaxWidth().background(Palette.agent, RoundedCornerShape(8.dp)).padding(10.dp)) {
                MarkdownText(t.result, color = Palette.ink)
            }
        } else if (t.result.isNotEmpty()) {
            Text("Tap for its report", fontSize = 12.sp, color = Palette.blue)
        }
    }
}

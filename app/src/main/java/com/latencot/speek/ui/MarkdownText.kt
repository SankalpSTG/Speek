package com.latencot.speek.ui

import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.latencot.speek.data.Markdown

/** An agent's Markdown, drawn: headings, lists, code blocks, quotes, tables, inline styles, links. */
@Composable
fun MarkdownText(text: String, color: Color, linkColor: Color = Palette.blue, codeBg: Color = Palette.agent) {
    val blocks = remember(text) { Markdown.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { MdBlock(it, color, linkColor, codeBg) }
    }
}

@Composable
private fun MdBlock(b: Markdown.Block, color: Color, linkColor: Color, codeBg: Color) {
    when (b) {
        is Markdown.Block.Heading -> Text(
            runs(b.text, linkColor, codeBg),
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = when (b.level) { 1 -> 19.sp; 2 -> 17.sp; else -> 15.sp },
            lineHeight = when (b.level) { 1 -> 25.sp; 2 -> 23.sp; else -> 21.sp },
        )
        is Markdown.Block.Paragraph -> Text(runs(b.text, linkColor, codeBg), color = color, fontSize = 15.sp, lineHeight = 21.sp)
        is Markdown.Block.Item -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (b.depth > 0) Spacer(Modifier.width((14 * b.depth).dp))
            Text(b.marker, color = color, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.widthIn(min = 12.dp))
            Text(runs(b.text, linkColor, codeBg), color = color, fontSize = 15.sp, lineHeight = 21.sp)
        }
        is Markdown.Block.Code -> Box(
            Modifier
                .fillMaxWidth()
                .background(codeBg, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(b.code, color = color, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace, softWrap = false)
        }
        is Markdown.Block.Quote -> Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(color.copy(alpha = 0.3f), RoundedCornerShape(2.dp)))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                b.blocks.forEach { MdBlock(it, color.copy(alpha = 0.8f), linkColor, codeBg) }
            }
        }
        is Markdown.Block.Table -> Box(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.border(1.dp, color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))) {
                TableRow(b.header, header = true, color, linkColor, codeBg)
                b.rows.forEach { TableRow(it, header = false, color, linkColor, codeBg) }
            }
        }
        Markdown.Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(color.copy(alpha = 0.2f)))
    }
}

@Composable
private fun TableRow(cells: List<List<Markdown.Run>>, header: Boolean, color: Color, linkColor: Color, codeBg: Color) {
    Row(Modifier.background(if (header) color.copy(alpha = 0.06f) else Color.Transparent)) {
        cells.forEach { cell ->
            Box(Modifier.widthIn(min = 64.dp, max = 220.dp).padding(horizontal = 8.dp, vertical = 5.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    runs(cell, linkColor, codeBg),
                    color = color,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun runs(runs: List<Markdown.Run>, linkColor: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    runs.forEach { r ->
        val style = SpanStyle(
            fontWeight = if (r.bold) FontWeight.Bold else null,
            fontStyle = if (r.italic) FontStyle.Italic else null,
            fontFamily = if (r.code) FontFamily.Monospace else null,
            background = if (r.code) codeBg else Color.Unspecified,
            fontSize = if (r.code) 13.sp else TextUnit.Unspecified,
            textDecoration = if (r.strike) TextDecoration.LineThrough else null,
        )
        if (r.link != null) {
            withLink(LinkAnnotation.Url(r.link, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(r.text) }
            }
        } else {
            withStyle(style) { append(r.text) }
        }
    }
}

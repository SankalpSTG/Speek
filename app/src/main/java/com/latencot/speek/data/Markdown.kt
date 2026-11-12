package com.latencot.speek.data

/**
 * The Markdown agents write (CommonMark plus GitHub tables and strikethrough), parsed into blocks
 * and inline runs for the chat bubbles. Plain Kotlin, so it runs without Android.
 */
object Markdown {

    sealed interface Block {
        data class Heading(val level: Int, val text: List<Run>) : Block
        data class Paragraph(val text: List<Run>) : Block
        data class Code(val lang: String, val code: String) : Block
        data class Quote(val blocks: List<Block>) : Block
        /** One list item: [depth] 0 is the outer list; [marker] is "•" or "3.". */
        data class Item(val depth: Int, val marker: String, val text: List<Run>) : Block
        data class Table(val header: List<List<Run>>, val rows: List<List<List<Run>>>) : Block
        data object Rule : Block
    }

    /** A stretch of text with one style; [link] is set for links (their URL). */
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null,
    )

    private val fence = Regex("""^\s{0,3}(```+|~~~+)\s*([\w+#.-]*).*$""")
    private val heading = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val rule = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val bullet = Regex("""^(\s*)([-*+])\s+(.*)$""")
    private val ordered = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
    private val quote = Regex("""^\s{0,3}>\s?(.*)$""")
    private val tableSep = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")

    fun parse(text: String): List<Block> = blocks(text.replace("\r\n", "\n").split("\n"))

    private fun blocks(lines: List<String>): List<Block> {
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) out += Block.Paragraph(inline(para.joinToString("\n") { it.trim() }))
            para.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val f = fence.matchEntire(line)
            if (f != null) {
                flush()
                val close = f.groupValues[1]
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(close)) body += lines[i++]
                out += Block.Code(f.groupValues[2], body.joinToString("\n"))
                i++ // the closing fence (or the end)
                continue
            }
            if (line.isBlank()) {
                flush(); i++; continue
            }
            val h = heading.matchEntire(line)
            if (h != null) {
                flush()
                out += Block.Heading(h.groupValues[1].length, inline(h.groupValues[2]))
                i++
                continue
            }
            if (rule.matches(line)) {
                flush(); out += Block.Rule; i++; continue
            }
            if (quote.matches(line)) {
                flush()
                val inner = mutableListOf<String>()
                while (i < lines.size && quote.matches(lines[i])) inner += quote.matchEntire(lines[i++])!!.groupValues[1]
                out += Block.Quote(blocks(inner))
                continue
            }
            if (line.trimStart().startsWith("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                flush()
                val header = cells(line)
                i += 2
                val rows = mutableListOf<List<List<Run>>>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) rows += cells(lines[i++]).let { r ->
                    List(header.size) { c -> r.getOrElse(c) { emptyList() } }
                }
                out += Block.Table(header, rows)
                continue
            }
            val b = bullet.matchEntire(line)
            val o = ordered.matchEntire(line)
            if (b != null || o != null) {
                flush()
                val m = (b ?: o)!!
                val marker = if (b != null) "•" else "${m.groupValues[2]}."
                val item = StringBuilder(m.groupValues[3])
                i++
                // A lazy continuation: an indented line that starts nothing new belongs to the item.
                while (i < lines.size && lines[i].isNotBlank() && lines[i].startsWith(" ") &&
                    bullet.matchEntire(lines[i]) == null && ordered.matchEntire(lines[i]) == null
                ) item.append("\n").append(lines[i++].trim())
                out += Block.Item(depth(m.groupValues[1]), marker, inline(item.toString()))
                continue
            }
            para += line
            i++
        }
        flush()
        return out
    }

    /** Two or more spaces (or a tab) of indent per level. */
    private fun depth(indent: String): Int = indent.replace("\t", "    ").length / 2

    private fun cells(row: String): List<List<Run>> {
        var t = row.trim()
        if (t.startsWith("|")) t = t.drop(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        return t.split(Regex("""(?<!\\)\|""")).map { inline(it.trim().replace("\\|", "|")) }
    }

    /** Inline Markdown: `code`, **bold**, *italic* / _italic_, ~~strike~~, [links](url), bare URLs. */
    fun inline(text: String): List<Run> {
        val out = mutableListOf<Run>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        var strike = false
        fun emit(run: Run? = null) {
            if (buf.isNotEmpty()) out += Run(buf.toString(), bold, italic, false, strike)
            buf.clear()
            run?.let { out += it }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val rest = text.substring(i)
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE -> {
                    buf.append(text[i + 1]); i += 2
                }
                c == '`' -> {
                    val ticks = rest.takeWhile { it == '`' }
                    val end = text.indexOf(ticks, i + ticks.length)
                    if (end < 0) {
                        buf.append(ticks); i += ticks.length
                    } else {
                        emit(Run(text.substring(i + ticks.length, end).trim(' '), bold, italic, code = true, strike = strike))
                        i = end + ticks.length
                    }
                }
                rest.startsWith("**") || rest.startsWith("__") -> {
                    val mark = rest.take(2)
                    if (bold || closes(text, i + 2, mark)) {
                        emit(); bold = !bold
                    } else buf.append(mark)
                    i += 2
                }
                rest.startsWith("~~") -> {
                    if (strike || closes(text, i + 2, "~~")) {
                        emit(); strike = !strike
                    } else buf.append("~~")
                    i += 2
                }
                (c == '*' || c == '_') && italicFlip(text, i, italic) -> {
                    emit(); italic = !italic; i++
                }
                c == '[' -> {
                    val m = link.matchAt(text, i)
                    if (m != null) {
                        emit()
                        inline(m.groupValues[1]).forEach { r -> out += r.copy(bold = r.bold || bold, italic = r.italic || italic, link = m.groupValues[2]) }
                        i += m.value.length
                    } else {
                        buf.append(c); i++
                    }
                }
                (c == 'h' && (rest.startsWith("http://") || rest.startsWith("https://"))) &&
                    (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                    val url = bareUrl.matchAt(text, i)!!.value.trimEnd('.', ',', ')', ';', ':', '!', '?')
                    emit(Run(url, bold, italic, strike = strike, link = url))
                    i += url.length
                }
                else -> {
                    buf.append(c); i++
                }
            }
        }
        emit()
        return out
    }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~>"
    private val link = Regex("""\[([^\]\n]+)]\((\S+?)(?:\s+"[^"]*")?\)""")
    private val bareUrl = Regex("""https?://[^\s<>"]+""")

    /** An opening ** / ~~ only counts when it closes later on (so "2 ** 3" stays as typed). */
    private fun closes(text: String, from: Int, mark: String): Boolean {
        val end = text.indexOf(mark, from)
        return end > from && !text[from].isWhitespace()
    }

    /** A single * or _ flips italics at a word edge: snake_case and 2*3*4 stay as typed. */
    private fun italicFlip(text: String, i: Int, italic: Boolean): Boolean {
        val c = text[i]
        val before = text.getOrNull(i - 1)
        val after = text.getOrNull(i + 1)
        if (after == c || before == c) return false
        return if (!italic) {
            after != null && !after.isWhitespace() && (before == null || !before.isLetterOrDigit()) &&
                text.indexOf(c, i + 1).let { end -> end > i + 1 && (text.getOrNull(end + 1)?.isLetterOrDigit() != true) }
        } else {
            before != null && !before.isWhitespace() && (after == null || !after.isLetterOrDigit())
        }
    }

    /** One line for the chat list's preview. */
    fun oneLine(text: String): String = plain(text).split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")

    /** Plain text for speech and previews: the words, without the markup. */
    fun plain(text: String): String = parse(text).joinToString("\n") { plain(it) }

    private fun plain(b: Block): String = when (b) {
        is Block.Heading -> b.text.joinToString("") { it.text }
        is Block.Paragraph -> b.text.joinToString("") { it.text }
        is Block.Code -> b.code
        is Block.Quote -> b.blocks.joinToString("\n") { plain(it) }
        is Block.Item -> b.text.joinToString("") { it.text }
        is Block.Table -> (listOf(b.header) + b.rows).joinToString("\n") { r -> r.joinToString(", ") { c -> c.joinToString("") { it.text } } }
        Block.Rule -> ""
    }
}

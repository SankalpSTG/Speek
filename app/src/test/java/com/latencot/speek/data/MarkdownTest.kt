package com.latencot.speek.data

import com.latencot.speek.data.Markdown.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun blocksAreRecognised() {
        val blocks = Markdown.parse("# Title\n\nSome text\n\n- one\n- two\n\n```kotlin\nval x = 1\n```\n\n> quoted\n\n---")
        assertTrue(blocks[0] is Block.Heading)
        assertTrue(blocks[1] is Block.Paragraph)
        assertEquals(listOf("one", "two"), blocks.filterIsInstance<Block.Item>().map { it.text.joinToString("") { r -> r.text } })
        assertEquals("val x = 1", (blocks.first { it is Block.Code } as Block.Code).code)
        assertTrue(blocks.any { it is Block.Quote })
        assertTrue(blocks.last() is Block.Rule)
    }

    @Test
    fun inlineStylesAndLinks() {
        val runs = (Markdown.parse("**bold** and `code` and [docs](https://example.com)").single() as Block.Paragraph).text
        assertTrue(runs.first { it.text == "bold" }.bold)
        assertTrue(runs.first { it.text == "code" }.code)
        assertEquals("https://example.com", runs.first { it.text == "docs" }.link)
    }

    @Test
    fun tablesKeepHeaderAndRows() {
        val table = Markdown.parse("| a | b |\n|---|---|\n| 1 | 2 |").single() as Block.Table
        assertEquals(2, table.header.size)
        assertEquals(1, table.rows.size)
    }

    @Test
    fun plainTextDropsTheMarkupForSpeech() {
        assertEquals("Done. See the log", Markdown.oneLine("**Done.**\n\nSee the `log`"))
    }
}

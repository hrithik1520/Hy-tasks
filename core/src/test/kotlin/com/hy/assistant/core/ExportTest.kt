package com.hy.assistant.core

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportTest {
    private val sample = """
        # Phone comparison
        Here is a **quick** summary with *notes* & `code` <tags>.

        | Phone | Price (₹) | Notes |
        |---|---:|---|
        | Pixel 10 | 79,999 | Great "camera" |
        | iPhone 17 | 82,900 | =SUM(A1) trick |

        - First point
        - Second **bold** point
          - Nested
        1. Step one
    """.trimIndent()

    @Test
    fun detectsRequestedFormat() {
        assertEquals(ExportFormat.CSV, Export.requestedFormat("make a CSV of my unread chats"))
        assertEquals(ExportFormat.CSV, Export.requestedFormat("put it in a spreadsheet"))
        assertEquals(ExportFormat.DOCX, Export.requestedFormat("write a leave letter as a Word file"))
        assertEquals(ExportFormat.DOCX, Export.requestedFormat("save it as docx"))
        assertEquals(ExportFormat.MD, Export.requestedFormat("export notes as .md"))
        assertEquals(ExportFormat.TXT, Export.requestedFormat("save as a text file"))
        assertNull(Export.requestedFormat("in a word, how was the movie?"))
        assertNull(Export.requestedFormat("what's the weather"))
    }

    @Test
    fun parsesMarkdownBlocks() {
        val b = Export.parse(sample)
        assertEquals(Export.Block.Heading(1, "Phone comparison"), b[0])
        assertTrue(b[1] is Export.Block.Paragraph)
        val table = b[2] as Export.Block.Table
        assertEquals(listOf("Phone", "Price (₹)", "Notes"), table.rows[0])
        assertEquals(3, table.rows.size)
        assertEquals(Export.Block.Bullet("Second **bold** point", null, 0), b[4])
        assertEquals(1, (b[5] as Export.Block.Bullet).indent)
        assertEquals("1.", (b[6] as Export.Block.Bullet).marker)
    }

    @Test
    fun csvQuotesAndBlocksFormulas() {
        val csv = Export.toCsv(sample)
        val lines = csv.trimEnd().split("\r\n")
        assertEquals("Phone,Price (₹),Notes", lines[0])
        assertEquals("Pixel 10,\"79,999\",\"Great \"\"camera\"\"\"", lines[1])
        assertEquals("iPhone 17,\"82,900\",'=SUM(A1) trick", lines[2])
        assertEquals("-5", Export.csvCell("-5"))
    }

    @Test
    fun csvWithoutTableUsesListItems() {
        val csv = Export.toCsv("Unread:\n- Rahul: hi\n- Mom: call me")
        assertEquals("Item\r\nUnread:\r\nRahul: hi\r\nMom: call me\r\n", csv)
    }

    @Test
    fun plainText() {
        val t = Export.toPlainText(sample)
        assertTrue(t.startsWith("PHONE COMPARISON"))
        assertTrue(t.contains("Here is a quick summary with notes & code <tags>."))
        assertTrue(t.contains("• First point\n• Second bold point\n  • Nested\n1. Step one"))
        assertTrue(t.contains("Pixel 10   79,999"))
    }

    @Test
    fun docxIsAValidPackage() {
        val bytes = Export.build(sample, ExportFormat.DOCX, "Phones")
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name] = z.readBytes()
            }
        }
        assertEquals(
            setOf("[Content_Types].xml", "_rels/.rels", "word/_rels/document.xml.rels", "word/styles.xml", "docProps/core.xml", "word/document.xml"),
            entries.keys,
        )
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        entries.values.forEach { f.newDocumentBuilder().parse(ByteArrayInputStream(it)) } // all well-formed XML
        val doc = String(entries["word/document.xml"]!!)
        assertTrue(doc.contains("w:pStyle w:val=\"Heading1\""))
        assertTrue(doc.contains("<w:b/></w:rPr><w:t xml:space=\"preserve\">quick</w:t>"))
        assertTrue(doc.contains("&amp; ") && doc.contains("&lt;tags&gt;"))
        assertTrue(doc.contains("<w:tbl>") && doc.contains("Great &quot;camera&quot;"))
        System.getProperty("hy.docxOut")?.let { java.io.File(it).writeBytes(bytes) }
    }

    @Test
    fun docxDropsInvalidXmlChars() {
        assertEquals("ab", Export.Docx.esc("a\u0001b"))
        assertEquals("Hi 😀", Export.Docx.esc("Hi 😀"))
    }

    @Test
    fun fileNames() {
        assertEquals("compare-iphone-17-vs-pixel-10-20261002-1530.docx", Export.fileName("Compare iPhone 17 vs Pixel 10!", ExportFormat.DOCX, "20261002-1530"))
    }
}

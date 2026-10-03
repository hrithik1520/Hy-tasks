package com.hy.assistant.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val ext: String, val mime: String, val label: String) {
    MD("md", "text/markdown", "Markdown"),
    CSV("csv", "text/csv", "CSV"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word"),
    TXT("txt", "text/plain", "Text"),
}

/** Turns an assistant answer (markdown-ish text) into .md / .csv / .docx / .txt files. Pure Kotlin, no network. */
object Export {

    // ---- Which format did the user ask for? ---------------------------------------------

    private val formatPatterns = listOf(
        ExportFormat.CSV to Regex("""\bcsv\b|spreadsheet|\bexcel\b|\bxlsx\b|google sheets?"""),
        ExportFormat.DOCX to Regex("""\bdocx\b|\bword (file|doc|document)\b|\bms word\b|\b(in|as|into) word\b|\.doc\b"""),
        ExportFormat.MD to Regex("""\bmarkdown\b|\.md\b|\bmd file\b"""),
        ExportFormat.TXT to Regex("""\btxt\b|\btext file\b|\.txt\b|\bnotepad\b"""),
    )

    /** e.g. "make a CSV of my unread chats" → CSV; null when no file format is mentioned. */
    fun requestedFormat(request: String): ExportFormat? {
        val t = request.lowercase()
        return formatPatterns.firstOrNull { (_, r) -> r.containsMatchIn(t) }?.first
    }

    /** Extra instruction so the model writes content that converts well (not "here's your file"). */
    fun promptHint(format: ExportFormat): String = when (format) {
        ExportFormat.CSV -> " Present the data as ONE markdown table with a header row (it will be saved as a CSV file)."
        ExportFormat.DOCX -> " Write the full document using markdown headings, lists and tables (it will be saved as a Word file)."
        ExportFormat.MD -> " Write it in markdown (it will be saved as a .md file)."
        ExportFormat.TXT -> " Write it as plain text (it will be saved as a .txt file)."
    }

    fun fileName(title: String, format: ExportFormat, stamp: String): String {
        val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "hy" }
        return "$slug-$stamp.${format.ext}"
    }

    fun build(text: String, format: ExportFormat, title: String): ByteArray = when (format) {
        ExportFormat.MD -> text.trim().plus("\n").toByteArray()
        ExportFormat.TXT -> toPlainText(text).toByteArray()
        ExportFormat.CSV -> toCsv(text).toByteArray()
        ExportFormat.DOCX -> Docx.build(parse(text), title)
    }

    // ---- Markdown parsing -------------------------------------------------------------------

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Bullet(val text: String, val marker: String?, val indent: Int) : Block
        data class Paragraph(val text: String) : Block
        data class Table(val rows: List<List<String>>) : Block
        data class Code(val text: String) : Block
    }

    private val tableSep = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
    private val bullet = Regex("""^(\s*)([-*•+]|\d+[.)])\s+(.*)$""")
    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*$""")

    fun parse(md: String): List<Block> {
        val lines = md.replace("\r\n", "\n").lines()
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) out += Block.Paragraph(para.joinToString(" ").trim())
            para.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") -> {
                    flush()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    out += Block.Code(code.joinToString("\n"))
                }
                t.isEmpty() -> flush()
                isTableRow(t) && i + 1 < lines.size && tableSep.matches(lines[i + 1].trim()) -> {
                    flush()
                    val rows = mutableListOf(cells(t))
                    i += 2
                    while (i < lines.size && isTableRow(lines[i].trim())) rows += cells(lines[i++].trim())
                    i--
                    out += Block.Table(rows)
                }
                heading.matches(t) -> {
                    flush()
                    val m = heading.find(t)!!
                    out += Block.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                bullet.matches(line) -> {
                    flush()
                    val m = bullet.find(line)!!
                    val marker = m.groupValues[2].takeIf { it.first().isDigit() }
                    out += Block.Bullet(m.groupValues[3], marker, m.groupValues[1].length / 2)
                }
                else -> para += t
            }
            i++
        }
        flush()
        return out
    }

    private fun isTableRow(t: String) = t.startsWith("|") && t.count { it == '|' } >= 2

    private fun cells(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split(Regex("""(?<!\\)\|""")).map { stripInline(it.trim().replace("\\|", "|")) }

    /** Removes **bold**, *italic*, `code` and [link](url) markup. */
    fun stripInline(s: String): String = s
        .replace(Regex("""\[([^\]]+)]\(([^)]+)\)"""), "$1 ($2)")
        .replace(Regex("""\*\*(.+?)\*\*|__(.+?)__""")) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        .replace(Regex("""(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])""")) { it.groupValues[1] }
        .replace(Regex("""`([^`]+)`"""), "$1")

    // ---- CSV ----------------------------------------------------------------------------------

    /**
     * Every markdown table becomes CSV rows (tables separated by a blank line). Without a table,
     * list items / lines become a one-column CSV so the export still works.
     */
    fun toCsv(text: String): String {
        val blocks = parse(text)
        val tables = blocks.filterIsInstance<Block.Table>()
        val rows: List<List<String>> = if (tables.isNotEmpty()) {
            tables.flatMapIndexed { i, t -> (if (i > 0) listOf(emptyList<String>()) else emptyList()) + t.rows }
        } else {
            val items = blocks.mapNotNull {
                when (it) {
                    is Block.Bullet -> stripInline(it.text)
                    is Block.Paragraph -> stripInline(it.text)
                    is Block.Heading -> null
                    else -> null
                }
            }
            if (items.isEmpty()) listOf(listOf(stripInline(text.trim()))) else listOf(listOf("Item")) + items.map { listOf(it) }
        }
        return rows.joinToString("\r\n") { r -> r.joinToString(",") { csvCell(it) } } + "\r\n"
    }

    fun csvCell(v: String): String {
        // Guard against spreadsheet formula injection from web/chat content.
        val safe = if (v.isNotEmpty() && v[0] in "=+-@" && v.toDoubleOrNull() == null) "'$v" else v
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    // ---- Plain text ------------------------------------------------------------------------------

    fun toPlainText(text: String): String {
        val sb = StringBuilder()
        var prev: Block? = null
        for (b in parse(text)) {
            if (prev != null) sb.append(if (prev is Block.Bullet && b is Block.Bullet) "\n" else "\n\n")
            sb.append(
                when (b) {
                    is Block.Heading -> stripInline(b.text).let { if (b.level <= 2) it.uppercase() else it }
                    is Block.Bullet -> "  ".repeat(b.indent) + (b.marker ?: "•") + " " + stripInline(b.text)
                    is Block.Paragraph -> stripInline(b.text)
                    is Block.Code -> b.text
                    is Block.Table -> {
                        val w = IntArray(b.rows.maxOf { it.size })
                        b.rows.forEach { r -> r.forEachIndexed { i, c -> w[i] = maxOf(w[i], c.length) } }
                        b.rows.joinToString("\n") { r -> r.mapIndexed { i, c -> c.padEnd(w[i]) }.joinToString("  ").trimEnd() }
                    }
                },
            )
            prev = b
        }
        return sb.toString().trim() + "\n"
    }

    // ---- DOCX (Office Open XML, written by hand) ----------------------------------------------

    object Docx {
        fun build(blocks: List<Block>, title: String): ByteArray {
            val body = StringBuilder()
            if (blocks.none { it is Block.Heading } && title.isNotBlank()) body.append(heading(1, title))
            for (b in blocks) body.append(
                when (b) {
                    is Block.Heading -> heading(b.level, b.text)
                    is Block.Paragraph -> para(runs(b.text))
                    is Block.Bullet -> para(runs((b.marker ?: "•") + "\t" + b.text), indent = 360 + 360 * b.indent)
                    is Block.Code -> b.text.lines().joinToString("") { para("<w:r><w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/><w:sz w:val=\"20\"/></w:rPr>${t(it)}</w:r>") }
                    is Block.Table -> table(b.rows)
                },
            )
            val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z ->
                fun put(name: String, content: String) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(content.toByteArray(Charsets.UTF_8))
                    z.closeEntry()
                }
                put("[Content_Types].xml", CONTENT_TYPES)
                put("_rels/.rels", RELS)
                put("word/_rels/document.xml.rels", DOC_RELS)
                put("word/styles.xml", STYLES)
                put("docProps/core.xml", core(title))
                put("word/document.xml", document)
            }
            return out.toByteArray()
        }

        private fun heading(level: Int, text: String) =
            "<w:p><w:pPr><w:pStyle w:val=\"Heading${level.coerceIn(1, 3)}\"/></w:pPr>${runs(text)}</w:p>"

        private fun para(runs: String, indent: Int = 0) =
            "<w:p>" + (if (indent > 0) "<w:pPr><w:ind w:left=\"$indent\" w:hanging=\"360\"/></w:pPr>" else "") + runs + "</w:p>"

        private fun table(rows: List<List<String>>): String {
            val cols = rows.maxOf { it.size }
            val sb = StringBuilder("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/>")
            sb.append("<w:tblBorders>")
            for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) sb.append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>")
            sb.append("</w:tblBorders></w:tblPr><w:tblGrid>")
            repeat(cols) { sb.append("<w:gridCol/>") }
            sb.append("</w:tblGrid>")
            rows.forEachIndexed { r, row ->
                sb.append("<w:tr>")
                for (c in 0 until cols) {
                    val text = row.getOrElse(c) { "" }
                    sb.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr><w:p>")
                    sb.append(if (r == 0) "<w:r><w:rPr><w:b/></w:rPr>${t(text)}</w:r>" else runs(text))
                    sb.append("</w:p></w:tc>")
                }
                sb.append("</w:tr>")
            }
            return sb.append("</w:tbl><w:p/>").toString()
        }

        /** Inline **bold**, *italic* and `code` → Word runs. */
        internal fun runs(text: String): String {
            val sb = StringBuilder()
            val token = Regex("""\*\*(.+?)\*\*|(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])|`([^`]+)`""")
            var last = 0
            for (m in token.findAll(text)) {
                if (m.range.first > last) sb.append("<w:r>${t(text.substring(last, m.range.first))}</w:r>")
                when {
                    m.groupValues[1].isNotEmpty() -> sb.append("<w:r><w:rPr><w:b/></w:rPr>${t(m.groupValues[1])}</w:r>")
                    m.groupValues[2].isNotEmpty() -> sb.append("<w:r><w:rPr><w:i/></w:rPr>${t(m.groupValues[2])}</w:r>")
                    else -> sb.append("<w:r><w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/></w:rPr>${t(m.groupValues[3])}</w:r>")
                }
                last = m.range.last + 1
            }
            if (last < text.length) sb.append("<w:r>${t(text.substring(last))}</w:r>")
            return sb.toString()
        }

        /** A text element; tabs become Word tabs, invalid XML characters are dropped. */
        private fun t(s: String): String = s.split('\t').joinToString("<w:tab/>") { "<w:t xml:space=\"preserve\">${esc(it)}</w:t>" }

        fun esc(s: String): String = buildString {
            for (c in s) when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\t' || c == '\n' || c == '\r' || c >= ' ' && c != '￾' && c != '￿' -> append(c)
            }
        }

        private fun core(title: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${esc(title)}</dc:title><dc:creator>Alfrid</dc:creator></cp:coreProperties>"""

        private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""

        private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""

        private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

        private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="30"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="160" w:after="60"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style><w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/></w:style></w:styles>"""
    }
}

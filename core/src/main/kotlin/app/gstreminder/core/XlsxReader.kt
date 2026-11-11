package app.gstreminder.core

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/** One row of a sheet. [number] is the 1-based Excel row number; [cells] are indexed by column (A = 0). */
data class SheetRow(val number: Int, val cells: List<String>) {
    operator fun get(col: Int): String = cells.getOrNull(col) ?: ""
    val isBlank: Boolean get() = cells.all { it.isBlank() }
}

data class Sheet(val name: String, val rows: List<SheetRow>)

/**
 * Minimal, dependency-free .xlsx reader (an .xlsx file is a zip of XML parts).
 * Reads cell values as text only, which is all this app needs. Uses SAX, which is
 * available both on the JVM and on Android.
 */
object XlsxReader {

    fun read(input: InputStream): List<Sheet> {
        val parts = unzip(input)
        val workbook = parts["xl/workbook.xml"]
            ?: throw ImportException("Not a valid .xlsx file (workbook missing). If this is an old .xls file, open it in Excel and save as .xlsx.")
        val rels = parts["xl/_rels/workbook.xml.rels"]?.let { parseRels(it) } ?: emptyMap()
        val shared = parts["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()

        return parseWorkbookSheets(workbook).mapIndexedNotNull { index, (name, relId) ->
            val target = rels[relId]?.let { resolveTarget(it) } ?: "xl/worksheets/sheet${index + 1}.xml"
            parts[target]?.let { Sheet(name, parseSheet(it, shared)) }
        }
    }

    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) out[entry.name.removePrefix("/")] = zip.readBytes()
            }
        }
        if (out.isEmpty()) throw ImportException("The file is empty or not an .xlsx file.")
        return out
    }

    private fun resolveTarget(target: String): String =
        if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")

    private fun parse(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
        ).forEach { runCatching { factory.setFeature(it, false) } }
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
    }

    /** Element name without any namespace prefix ("x:row" -> "row"). */
    private fun local(qName: String) = qName.substringAfter(':')

    private fun parseWorkbookSheets(bytes: ByteArray): List<Pair<String, String>> {
        val sheets = ArrayList<Pair<String, String>>()
        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, ln: String?, qName: String, attrs: Attributes) {
                if (local(qName) != "sheet") return
                var name = ""
                var relId = ""
                for (i in 0 until attrs.length) {
                    val a = attrs.getQName(i)
                    when {
                        a == "name" -> name = attrs.getValue(i)
                        a.endsWith(":id") -> relId = attrs.getValue(i)
                    }
                }
                sheets += name to relId
            }
        })
        return sheets
    }

    private fun parseRels(bytes: ByteArray): Map<String, String> {
        val map = HashMap<String, String>()
        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, ln: String?, qName: String, attrs: Attributes) {
                if (local(qName) == "Relationship") {
                    val id = attrs.getValue("Id")
                    val target = attrs.getValue("Target")
                    if (id != null && target != null) map[id] = target
                }
            }
        })
        return map
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val list = ArrayList<String>()
        parse(bytes, object : DefaultHandler() {
            val sb = StringBuilder()
            var inT = false
            var phoneticDepth = 0

            override fun startElement(uri: String?, ln: String?, qName: String, attrs: Attributes) {
                when (local(qName)) {
                    "si" -> sb.setLength(0)
                    "rPh" -> phoneticDepth++
                    "t" -> inT = phoneticDepth == 0
                }
            }

            override fun endElement(uri: String?, ln: String?, qName: String) {
                when (local(qName)) {
                    "si" -> list += sb.toString()
                    "rPh" -> phoneticDepth--
                    "t" -> inT = false
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inT) sb.appendRange(ch, start, start + length)
            }
        })
        return list
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<SheetRow> {
        val rows = ArrayList<SheetRow>()
        parse(bytes, object : DefaultHandler() {
            var rowNumber = 0
            var cells = ArrayList<String>()
            var col = -1
            var type = ""
            val value = StringBuilder()
            var capture = false

            override fun startElement(uri: String?, ln: String?, qName: String, attrs: Attributes) {
                when (local(qName)) {
                    "row" -> {
                        rowNumber = attrs.getValue("r")?.toIntOrNull() ?: (rowNumber + 1)
                        cells = ArrayList()
                        col = -1
                    }
                    "c" -> {
                        col = attrs.getValue("r")?.let { columnIndex(it) } ?: (col + 1)
                        type = attrs.getValue("t") ?: ""
                        value.setLength(0)
                    }
                    "v", "t" -> capture = true
                }
            }

            override fun endElement(uri: String?, ln: String?, qName: String) {
                when (local(qName)) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val raw = value.toString()
                        val text = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "b" -> if (raw == "1") "TRUE" else "FALSE"
                            "inlineStr", "str", "e" -> raw
                            else -> plainNumber(raw)
                        }
                        while (cells.size < col) cells += ""
                        if (cells.size == col) cells += text else cells[col] = text
                    }
                    "row" -> rows += SheetRow(rowNumber, cells)
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture) value.appendRange(ch, start, start + length)
            }
        })
        return rows
    }

    /** "AB12" -> 27 (0-based column index). */
    internal fun columnIndex(ref: String): Int {
        var n = 0
        for (c in ref) {
            if (!c.isLetter()) break
            n = n * 26 + (c.uppercaseChar() - 'A' + 1)
        }
        return n - 1
    }

    /** Numeric cells: "9940496850" / "9.94049685E9" / "12.0" -> "9940496850" / "9940496850" / "12". */
    internal fun plainNumber(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return runCatching { BigDecimal(t).stripTrailingZeros().toPlainString() }.getOrDefault(t)
    }
}

class ImportException(message: String) : Exception(message)

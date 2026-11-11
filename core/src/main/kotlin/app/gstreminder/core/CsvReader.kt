package app.gstreminder.core

import java.io.InputStream

/** Small RFC-4180 style CSV reader. Detects comma, semicolon or tab delimiters. */
object CsvReader {

    fun read(input: InputStream, name: String = "CSV"): Sheet {
        val text = input.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        val delimiter = listOf(',', ';', '\t').maxByOrNull { d -> firstLine.count { it == d } } ?: ','

        val rows = ArrayList<SheetRow>()
        var cells = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        var rowNumber = 1

        fun endCell() { cells += cell.toString(); cell.setLength(0) }
        fun endRow() {
            endCell()
            rows += SheetRow(rowNumber++, cells)
            cells = ArrayList()
        }

        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else inQuotes = false
                } else cell.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                delimiter -> endCell()
                '\r' -> {}
                '\n' -> endRow()
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || cells.isNotEmpty()) endRow()
        return Sheet(name, rows)
    }
}

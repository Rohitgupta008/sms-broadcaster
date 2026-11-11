package app.gstreminder.core

import java.time.YearMonth

/** Which returns a dealer still has to file. */
enum class Pending(val label: String) {
    NONE(""),
    /** The sheet has a single filed / not-filed column. */
    GST_RETURN("GST return"),
    GSTR1("GSTR-1"),
    GSTR3B("GSTR-3B"),
    BOTH("GSTR-1 & GSTR-3B");

    /** Lower sends first. */
    val priority: Int get() = when (this) {
        BOTH -> 0
        GST_RETURN -> 1
        GSTR3B -> 2
        GSTR1 -> 3
        NONE -> 9
    }
}

data class ParsedDealer(
    val row: Int,
    val gstin: String,
    val name: String,
    /** 10-digit mobile, or null if missing / invalid. */
    val phone: String?,
    val rawPhone: String,
    val pending: Pending,
    /** Other columns of the sheet (Office, Jurisdiction, Turnover, ...), in sheet order. */
    val extras: Map<String, String>,
)

data class ColumnMap(
    val gstin: Int,
    val name: Int,
    val phone: Int?,
    val status: Int?,
    val gstr1: Int?,
    val gstr3b: Int?,
    val headers: List<String>,
) {
    fun describe(): List<Pair<String, String?>> = listOf(
        "GSTIN" to headers.getOrNull(gstin),
        "Name" to headers.getOrNull(name),
        "Mobile" to phone?.let { headers.getOrNull(it) },
        "Status" to status?.let { headers.getOrNull(it) },
        "GSTR-1" to gstr1?.let { headers.getOrNull(it) },
        "GSTR-3B" to gstr3b?.let { headers.getOrNull(it) },
    ).filter { it.second != null || it.first in setOf("Mobile", "Status") }
}

data class ParsedImport(
    val sheetName: String,
    val columns: ColumnMap,
    val period: YearMonth?,
    val dealers: List<ParsedDealer>,
    val warnings: List<String>,
) {
    val total get() = dealers.size
    val filed get() = dealers.count { it.pending == Pending.NONE }
    val notFiled get() = dealers.count { it.pending != Pending.NONE }
    val toRemind get() = dealers.count { it.pending != Pending.NONE && it.phone != null }
    val notFiledNoMobile get() = dealers.count { it.pending != Pending.NONE && it.phone == null }
}

/** Turns raw sheets into a dealer list: finds the header row, maps columns, parses each row. */
object DealerSheetParser {

    fun parse(sheets: List<Sheet>): ParsedImport {
        data class Candidate(val sheet: Sheet, val headerIndex: Int, val columns: ColumnMap)

        val candidates = sheets.mapNotNull { sheet ->
            val idx = findHeaderRow(sheet) ?: return@mapNotNull null
            val cols = mapColumns(sheet.rows[idx].cells) ?: return@mapNotNull null
            Candidate(sheet, idx, cols)
        }
        if (candidates.isEmpty()) {
            throw ImportException("Could not find a header row with a \"GSTIN\" column and a name column in the first 10 rows.")
        }
        // Prefer a sheet that has mobile numbers; otherwise the first usable sheet.
        val chosen = candidates.firstOrNull { it.columns.phone != null } ?: candidates.first()
        val cols = chosen.columns
        val warnings = ArrayList<String>()
        if (cols.phone == null) warnings += "No mobile number column found. Add a column named \"Mobile\"."
        if (cols.status == null && cols.gstr1 == null && cols.gstr3b == null) {
            warnings += "No STATUS / GSTR-1 / GSTR-3B column found, so every dealer is treated as not filed."
        }

        // The period is looked for in title rows above the header (e.g. "Return Period: 05/2026") and in sheet names.
        val titleTexts = sheets.flatMap { s ->
            val headerAt = findHeaderRow(s) ?: minOf(3, s.rows.size)
            s.rows.take(headerAt).map { it.cells.joinToString(" ") } + s.name
        }
        val period = titleTexts.firstNotNullOfOrNull { PeriodExtractor.extract(it) }

        val skip = setOfNotNull(cols.gstin, cols.name, cols.phone, cols.status, cols.gstr1, cols.gstr3b)
        val extraCols = cols.headers.indices.filter { it !in skip && cols.headers[it].isNotBlank() && !isSerial(cols.headers[it]) }

        val seen = HashSet<String>()
        val dealers = ArrayList<ParsedDealer>()
        for (row in chosen.sheet.rows.drop(chosen.headerIndex + 1)) {
            val gstin = row[cols.gstin].trim().uppercase().replace(" ", "")
            val name = row[cols.name].trim().replace(Regex("\\s+"), " ")
            if (gstin.isEmpty()) {
                if (name.isNotEmpty()) warnings += "Row ${row.number}: \"$name\" has no GSTIN, skipped."
                continue
            }
            if (!seen.add(gstin)) {
                warnings += "Row ${row.number}: duplicate GSTIN $gstin, skipped."
                continue
            }
            if (!GSTIN_REGEX.matches(gstin)) warnings += "Row ${row.number}: GSTIN \"$gstin\" looks unusual."

            val rawPhone = cols.phone?.let { row[it] }?.trim().orEmpty()
            val phone = PhoneNormalizer.normalize(rawPhone)
            val pending = pendingFor(row, cols)
            if (pending != Pending.NONE && phone == null && rawPhone.isNotEmpty()) {
                warnings += "Row ${row.number}: mobile \"$rawPhone\" is not a valid 10-digit number."
            }
            dealers += ParsedDealer(
                row = row.number,
                gstin = gstin,
                name = name.ifEmpty { gstin },
                phone = phone,
                rawPhone = rawPhone,
                pending = pending,
                extras = extraCols.associate { cols.headers[it].trim() to row[it].trim() }.filterValues { it.isNotEmpty() },
            )
        }
        if (dealers.isEmpty()) throw ImportException("No dealer rows found under the header in sheet \"${chosen.sheet.name}\".")
        return ParsedImport(chosen.sheet.name, cols, period, dealers, warnings)
    }

    private const val HEADER_SCAN_ROWS = 10
    private val GSTIN_REGEX = Regex("^\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")

    private fun key(header: String) = header.lowercase().filter { it.isLetterOrDigit() }

    private fun isSerial(header: String) = key(header) in setOf("", "sr", "srn", "sno", "srno", "slno", "serial", "no", "sn")

    private fun findHeaderRow(sheet: Sheet): Int? =
        sheet.rows.take(HEADER_SCAN_ROWS).indexOfFirst { row -> row.cells.any { key(it).startsWith("gstin") || key(it) == "gstno" } }
            .takeIf { it >= 0 }

    internal fun mapColumns(headers: List<String>): ColumnMap? {
        val keys = headers.map { key(it) }
        fun find(vararg preds: (String) -> Boolean): Int? {
            for (p in preds) keys.indexOfFirst(p).takeIf { it >= 0 }?.let { return it }
            return null
        }
        val gstin = find({ it.startsWith("gstin") }, { it == "gstno" }) ?: return null
        val name = find(
            { it.contains("tradename") }, { it.contains("dealername") }, { it.contains("legalname") },
            { it.contains("partyname") }, { it.contains("firmname") }, { it.contains("name") },
        ) ?: return null
        val phone = find({ it.contains("mobile") }, { it.contains("phone") }, { it.startsWith("mob") }, { it.contains("contact") }, { it.contains("cell") })
        val gstr3b = find({ it.contains("3b") })
        val gstr1 = find({ (it.contains("gstr1") || it == "r1" || it.startsWith("r1")) && !it.contains("3b") })
        val status = find({ it.contains("status") && !it.contains("3b") && !it.contains("gstr1") }, { it == "filed" || it == "filingstatus" })
        return ColumnMap(gstin, name, phone, status, gstr1, gstr3b, headers)
    }

    private fun pendingFor(row: SheetRow, cols: ColumnMap): Pending {
        if (cols.gstr1 != null || cols.gstr3b != null) {
            val r1Pending = cols.gstr1?.let { !FilingStatusParser.isFiled(row[it]) } ?: false
            val r3bPending = cols.gstr3b?.let { !FilingStatusParser.isFiled(row[it]) } ?: false
            return when {
                r1Pending && r3bPending -> Pending.BOTH
                r1Pending -> Pending.GSTR1
                r3bPending -> Pending.GSTR3B
                else -> Pending.NONE
            }
        }
        val status = cols.status?.let { row[it] }
        return if (FilingStatusParser.isFiled(status)) Pending.NONE else Pending.GST_RETURN
    }
}

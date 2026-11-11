package app.gstreminder.core

import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

object PhoneNormalizer {
    private val LANDLINE = Regex("""^(\+91[\s-]?)?0?\d{2,4}[\s-]\d{6,8}$""")

    /**
     * Returns a 10-digit Indian mobile number, or null when none can be found.
     * Accepts "9876543210", "+91 98765 43210", "09876543210", "98765-43210",
     * "9876543210.0" and several numbers separated by "/" or "," (the first valid one wins).
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val candidates = raw.split('/', ',', ';', '|', '\n')
        for (c in candidates) {
            // Landline written with an STD code, e.g. "0731-2345678" or "0731 2345678".
            if (LANDLINE.matches(c.trim())) continue
            var digits = XlsxReader.plainNumber(c.trim()).filter { it.isDigit() }
            if (digits.length == 12 && digits.startsWith("91")) digits = digits.substring(2)
            if (digits.length == 11 && digits.startsWith("0")) digits = digits.substring(1)
            if (digits.length == 10 && digits[0] in '6'..'9') return digits
        }
        return null
    }
}

object FilingStatusParser {
    private val negative = listOf("not", "non", "pending", "no", "n", "false", "0", "✗", "✘", "unfiled", "due")
    private val positive = listOf("filed", "yes", "y", "true", "1", "done", "ok", "✓", "✔", "submitted", "complete", "completed")

    /** true = filed, false = not filed. Blank means not filed. */
    fun isFiled(raw: String?): Boolean {
        val t = raw?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (t.isEmpty()) return false
        val words = t.split(Regex("[^\\p{L}\\p{N}✓✔✗✘]+")).filter { it.isNotEmpty() }
        if (words.any { it in negative } || t.startsWith("not") || t.startsWith("non")) return false
        return words.any { it in positive } || t in positive
    }
}

object PeriodExtractor {
    private val numeric = Regex("""(?<!\d)(0?[1-9]|1[0-2])\s*[/\-.]\s*(20\d{2})(?!\d)""")
    private val named = Regex(
        """(?i)\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?[\s\-']*(20\d{2}|\d{2})\b"""
    )
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val display = DateTimeFormatter.ofPattern("MMM-yyyy", Locale.ENGLISH)

    /** Finds a return period such as "Return Period: 05/2026" or "Oct-2026" in free text. */
    fun extract(text: String): YearMonth? {
        val preferred = Regex("""(?i)period[^0-9a-z]*(.{0,20})""").find(text)?.groupValues?.get(1)
        for (candidate in listOfNotNull(preferred, text)) {
            numeric.find(candidate)?.let { m ->
                return YearMonth.of(m.groupValues[2].toInt(), m.groupValues[1].toInt())
            }
            named.find(candidate)?.let { m ->
                val month = months.indexOf(m.groupValues[1].lowercase(Locale.ROOT).take(3)) + 1
                var year = m.groupValues[2].toInt()
                if (year < 100) year += 2000
                return YearMonth.of(year, month)
            }
        }
        return null
    }

    /** "May-2026" */
    fun format(period: YearMonth): String = period.format(display)

    /** Parses what [format] produces, plus "05/2026". */
    fun parse(text: String): YearMonth? = extract(text)
}

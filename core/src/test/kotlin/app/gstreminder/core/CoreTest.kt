package app.gstreminder.core

import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XlsxImportTest {
    private fun sample() = javaClass.getResourceAsStream("/sample_nonfilers.xlsx")!!.use { XlsxReader.read(it) }

    @Test fun readsAllSheetsWithTitleRows() {
        val sheets = sample()
        assertEquals(listOf("Officer Non-Filers", "Sheet1"), sheets.map { it.name })
        assertTrue(sheets[0].rows[0][0].startsWith("Test Officer"))
        assertEquals("9000000001", sheets[1].rows[1][7]) // numeric cell, no ".0" / exponent
    }

    @Test fun picksSheetWithMobileAndParsesDealers() {
        val result = DealerSheetParser.parse(sample())
        assertEquals("Sheet1", result.sheetName)
        assertEquals(YearMonth.of(2026, 5), result.period)
        assertEquals(5, result.total)
        assertEquals(2, result.filed)
        assertEquals(3, result.notFiled)
        assertEquals(2, result.toRemind)          // Beta + Gamma
        assertEquals(1, result.notFiledNoMobile)  // Delta has no mobile
        assertEquals(emptyList(), result.warnings.filter { "GSTIN" in it })

        val gamma = result.dealers.first { it.gstin == "23AAAAA0003A1Z3" }
        assertEquals("8000000003", gamma.phone)
        assertEquals(Pending.GST_RETURN, gamma.pending)
        assertEquals(mapOf("Office" to "INDORE - 5(39)", "Jurisdiction" to "Centre", "Turnover" to "5 cr. To 50 Cr."), gamma.extras)

        val epsilon = result.dealers.first { it.gstin == "23AAAAA0005A1Z5" }
        assertEquals(Pending.NONE, epsilon.pending)
        assertNull(epsilon.phone) // "12345" is not a valid mobile
    }

    @Test fun headerOnFirstRowAndSeparateReturnColumns() {
        val csv = """
            Dealer Name,GSTIN,Mobile,GSTR-1,GSTR-3B
            A Traders,27AAAAA1111A1Z1,9876543210,Filed,Not Filed
            B Traders,27AAAAA2222A1Z2,9876543211,N,N
            C Traders,27AAAAA3333A1Z3,9876543212,Y,Y
            "D, Sons",27AAAAA4444A1Z4,9876543213,,Yes
        """.trimIndent()
        val result = DealerSheetParser.parse(listOf(CsvReader.read(csv.byteInputStream())))
        assertEquals(
            listOf(Pending.GSTR3B, Pending.BOTH, Pending.NONE, Pending.GSTR1),
            result.dealers.map { it.pending },
        )
        assertEquals("D, Sons", result.dealers[3].name)
        assertNull(result.period)
    }
}

class NormalizerTest {
    @Test fun phones() {
        assertEquals("9876543210", PhoneNormalizer.normalize("9876543210"))
        assertEquals("9876543210", PhoneNormalizer.normalize("+91 98765 43210"))
        assertEquals("9876543210", PhoneNormalizer.normalize("09876543210"))
        assertEquals("9876543210", PhoneNormalizer.normalize("9.87654321E9"))
        assertEquals("9876543210", PhoneNormalizer.normalize("0731-2345678 / 9876543210"))
        assertNull(PhoneNormalizer.normalize("0731 2345678"))
        assertNull(PhoneNormalizer.normalize(""))
        assertEquals("9876543210", PhoneNormalizer.normalize("98765 43210"))
        assertEquals("9876543210", PhoneNormalizer.normalize("+91-98765-43210"))
    }

    @Test fun statuses() {
        listOf("FILED", "Filed", "yes", "Y", "✓", "Done").forEach { assertTrue(FilingStatusParser.isFiled(it), it) }
        listOf("", "Not Filed", "NOT FILED", "No", "N", "Pending", "Non-Filer", null).forEach { assertFalse(FilingStatusParser.isFiled(it), "$it") }
    }

    @Test fun periods() {
        assertEquals(YearMonth.of(2026, 5), PeriodExtractor.extract("Officer | INDORE-5 | Return Period: 05/2026"))
        assertEquals(YearMonth.of(2026, 10), PeriodExtractor.extract("Non filers Oct-2026"))
        assertEquals(YearMonth.of(2026, 10), PeriodExtractor.extract("October 2026"))
        assertEquals("May-2026", PeriodExtractor.format(YearMonth.of(2026, 5)))
        assertNull(PeriodExtractor.extract("INDORE - 5(39)"))
    }
}

class MessageTest {
    @Test fun nameShortening() {
        assertEquals(
            listOf("Beta Oil Product Pvt Ltd", "Beta Oil Product"),
            MessageBuilder.nameVariants("M/S Beta Oil Product Pvt.Ltd."),
        )
        assertEquals("Alpha Thermo Private Ltd".let { "Alpha Thermo Pvt Ltd" }, MessageBuilder.nameVariants("Alpha Thermo Private Limited").first())
    }

    @Test fun defaultMessageFitsOneSms() {
        val m = MessageBuilder.build(
            MessageBuilder.DEFAULT_TEMPLATE,
            "Delta Roadside Infra Developers Private Limited",
            "23AAAAA0004A1Z4", "May-2026", Pending.GST_RETURN, MessageBuilder.DEFAULT_SENDER,
        )
        assertEquals(1, m.info.segments, m.text)
        assertTrue(m.info.gsm)
        assertTrue(m.text.startsWith("Dear Delta Roadside Infra Developers"), m.text)
        assertTrue(m.text.endsWith("-AC Indore-5"))
    }

    @Test fun veryLongNameIsCutToFit() {
        val m = MessageBuilder.build(
            MessageBuilder.DEFAULT_TEMPLATE,
            "Shree Mahalaxmi Agro Commodities Import Export And Warehousing Services Private Limited",
            "23AAAAA0004A1Z4", "May-2026", Pending.BOTH, MessageBuilder.DEFAULT_SENDER,
        )
        assertEquals(1, m.info.segments, m.text)
        assertTrue(m.text.contains("GSTR-1 & GSTR-3B"))
    }

    @Test fun unicodeIsSanitised() {
        val m = MessageBuilder.build("Dear {name} – pay ₹ late fee {sender}", "Café “Raj” ✨", "X", "P", Pending.GSTR1, "-Office")
        assertEquals("Dear Café \"Raj\" - pay Rs. late fee -Office", m.text)
        assertTrue(m.info.gsm)
    }

    @Test fun segmentCounting() {
        assertEquals(1, SmsText.segments("a".repeat(160)))
        assertEquals(2, SmsText.segments("a".repeat(161)))
        assertEquals(3, SmsText.segments("a".repeat(307)))
        assertEquals(1, SmsText.segments("अ".repeat(70)))
        assertEquals(2, SmsText.segments("अ".repeat(71)))
        assertEquals(2, SmsText.segments("[".repeat(81))) // extension chars count double
    }
}

/** Optional: `SAMPLE_XLSX=/path/file.xlsx ./gradlew -PcoreOnly :core:test` prints what the app would send. */
class RealFileDryRun {
    @Test fun dryRun() {
        val path = System.getenv("SAMPLE_XLSX") ?: return
        val result = DealerSheetParser.parse(java.io.File(path).inputStream().use { XlsxReader.read(it) })
        val period = result.period?.let { PeriodExtractor.format(it) } ?: "?"
        println("sheet=${result.sheetName} period=$period total=${result.total} filed=${result.filed} " +
            "notFiled=${result.notFiled} toRemind=${result.toRemind} noMobile=${result.notFiledNoMobile}")
        result.warnings.forEach { println("WARN $it") }
        result.dealers.filter { it.pending != Pending.NONE && it.phone != null }.forEach {
            val m = MessageBuilder.build(MessageBuilder.DEFAULT_TEMPLATE, it.name, it.gstin, period, it.pending, MessageBuilder.DEFAULT_SENDER)
            println("${m.info.chars}/${m.info.segments} ${m.text}")
        }
    }
}

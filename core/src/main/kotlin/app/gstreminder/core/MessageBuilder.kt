package app.gstreminder.core

/**
 * Builds the reminder SMS from a template. Placeholders:
 * {name} {gstin} {period} {returns} {sender}
 *
 * The dealer name is shortened step by step so the final message fits in a single SMS
 * (160 GSM characters) whenever possible.
 */
object MessageBuilder {

    const val DEFAULT_TEMPLATE =
        "Dear {name}, GSTIN {gstin}: {returns} for {period} not filed. Please file urgently to avoid late fee. {sender}"
    const val DEFAULT_SENDER = "-AC Indore-5"
    const val SINGLE_SMS_LIMIT = 160

    data class Message(val text: String, val info: SmsText.Info)

    fun build(
        template: String,
        name: String,
        gstin: String,
        period: String,
        pending: Pending,
        sender: String,
    ): Message {
        val returns = (if (pending == Pending.NONE) Pending.GST_RETURN else pending).label
        fun render(n: String) = SmsText.sanitize(
            template
                .replace("{name}", n)
                .replace("{gstin}", gstin)
                .replace("{period}", period)
                .replace("{returns}", returns)
                .replace("{sender}", sender.trim())
                .replace(Regex(" {2,}"), " ")
                .trim()
        )

        val variants = nameVariants(name)
        var text = render(variants.first())
        for (v in variants) {
            text = render(v)
            if (SmsText.info(text).segments == 1) break
        }
        if (SmsText.info(text).segments > 1 && template.contains("{name}")) {
            // Still too long: cut the shortest name variant word by word.
            val words = variants.last().split(' ').toMutableList()
            while (words.size > 1 && SmsText.info(text).segments > 1) {
                words.removeAt(words.lastIndex)
                text = render(words.joinToString(" "))
            }
        }
        return Message(text, SmsText.info(text))
    }

    /** "M/S Ghiya Oil Product Pvt.Ltd." -> ["Ghiya Oil Product Pvt Ltd", "Ghiya Oil Product"] */
    fun nameVariants(raw: String): List<String> {
        var n = SmsText.sanitize(raw).trim().replace(Regex("\\s+"), " ")
        n = n.replace(Regex("(?i)^(m\\s*/\\s*s\\.?|messrs\\.?|m/s\\.?)\\s*"), "")
        val replacements = listOf(
            "(?i)\\bprivate\\s+limited\\b" to "Pvt Ltd",
            "(?i)\\bpvt\\.?\\s*ltd\\.?" to "Pvt Ltd",
            "(?i)\\bprivate\\b" to "Pvt",
            "(?i)\\blimited\\b" to "Ltd",
            "(?i)\\bltd\\." to "Ltd",
            "(?i)\\bcompany\\b" to "Co",
            "(?i)\\benterprises\\b" to "Ent",
            "(?i)\\bindustries\\b" to "Inds",
            "(?i)\\s*&\\s*" to " & ",
        )
        val full = n.trim().trimEnd(',', '.').trim()
        var short = full
        for ((re, rep) in replacements.take(5)) short = short.replace(Regex(re), rep)
        short = short.replace(Regex("\\s+"), " ").trim().trimEnd(',', '.')
        val noSuffix = short.replace(Regex("(?i)\\s*\\b(pvt ltd|ltd|llp|pvt|inc|opc)\\b\\.?$"), "").trim().trimEnd(',', '.')
        var abbreviated = noSuffix
        for ((re, rep) in replacements.drop(5)) abbreviated = abbreviated.replace(Regex(re), rep)
        abbreviated = abbreviated.replace(Regex("\\s+"), " ").trim()
        return listOf(short, noSuffix, abbreviated).filter { it.isNotEmpty() }.distinct().ifEmpty { listOf(raw.trim()) }
    }
}

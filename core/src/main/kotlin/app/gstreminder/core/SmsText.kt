package app.gstreminder.core

import java.text.Normalizer

/**
 * SMS length rules. A message using only GSM-7 characters fits 160 characters in one SMS
 * (153 per part when split). A single non-GSM character (₹, Hindi, emoji, curly quotes)
 * switches the whole message to UCS-2: 70 characters per SMS (67 per part).
 */
object SmsText {
    private const val GSM_BASIC =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    private const val GSM_EXTENDED = "^{}\\[~]|€\u000C"

    fun isGsm(text: String) = text.all { it in GSM_BASIC || it in GSM_EXTENDED }

    data class Info(val chars: Int, val segments: Int, val gsm: Boolean, val perSegment: Int)

    fun info(text: String): Info {
        return if (isGsm(text)) {
            val septets = text.length + text.count { it in GSM_EXTENDED }
            val segs = if (septets <= 160) 1 else (septets + 152) / 153
            Info(septets, segs, true, if (segs == 1) 160 else 153)
        } else {
            val units = text.length
            val segs = if (units <= 70) 1 else (units + 66) / 67
            Info(units, segs, false, if (segs == 1) 70 else 67)
        }
    }

    fun segments(text: String) = info(text).segments

    /** Replaces common non-GSM characters with plain equivalents so the SMS stays in 160-char mode. */
    fun sanitize(text: String): String {
        val replaced = buildString {
            for (c in text) append(
                when (c) {
                    '‘', '’', '‚', '`', '´' -> "'"
                    '“', '”', '„' -> "\""
                    '–', '—', '−' -> "-"
                    '…' -> "..."
                    '₹' -> "Rs."
                    ' ', '\t' -> " "
                    else -> c.toString()
                }
            )
        }
        // Keep GSM characters; for others try the accent-free form ("ā" -> "a"), else drop them.
        return buildString {
            for (c in replaced) {
                if (c in GSM_BASIC || c in GSM_EXTENDED) append(c)
                else Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
                    .filter { it in GSM_BASIC || it in GSM_EXTENDED }
                    .let { append(it) }
            }
        }.replace(Regex(" {2,}"), " ")
    }
}

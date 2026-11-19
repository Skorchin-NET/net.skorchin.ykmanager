package net.skorchin.ykmanager.yubi

/** One part of the inventory report, e.g. "PIV" with its rows. */
data class InvSection(
    val title: String,
    val rows: List<Pair<String, String>> = emptyList(),
    val notes: List<String> = emptyList(),
    /** Optional sub-groups (one per slot, account, key...). */
    val groups: List<InvSection> = emptyList(),
)

data class InventoryReport(val title: String, val generatedAt: String, val sections: List<InvSection>)

/**
 * Renders the inventory as printable HTML or plain text. Pure functions (no Android types)
 * so they can be unit-tested. The report never contains secrets, OTP codes or PINs.
 */
object Inventory {

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    fun html(r: InventoryReport): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
        append(escape(r.title))
        append("</title><style>")
        append(
            "body{font-family:sans-serif;font-size:10pt;color:#111;margin:0}" +
                "h1{font-size:16pt;margin:0 0 2pt}p.meta{color:#555;margin:0 0 10pt}" +
                "h2{font-size:12pt;border-bottom:1px solid #999;margin:14pt 0 4pt;page-break-after:avoid}" +
                "h3{font-size:10.5pt;margin:8pt 0 2pt;page-break-after:avoid}" +
                "table{border-collapse:collapse;width:100%;page-break-inside:avoid}" +
                "td{padding:2pt 6pt 2pt 0;vertical-align:top}td.k{width:34%;color:#444}" +
                "td.v{word-break:break-word}p.note{color:#555;font-style:italic;margin:2pt 0}" +
                "footer{margin-top:16pt;color:#777;font-size:8pt}"
        )
        append("</style></head><body>")
        append("<h1>").append(escape(r.title)).append("</h1>")
        append("<p class=\"meta\">Generated ").append(escape(r.generatedAt)).append(" by YK Manager</p>")
        for (s in r.sections) section(s, level = 2)
        append("<footer>This report lists configuration only. It contains no secrets, PINs or one-time codes.</footer>")
        append("</body></html>")
    }

    private fun StringBuilder.section(s: InvSection, level: Int) {
        append("<h$level>").append(escape(s.title)).append("</h$level>")
        if (s.rows.isNotEmpty()) {
            append("<table>")
            for ((k, v) in s.rows) {
                append("<tr><td class=\"k\">").append(escape(k)).append("</td><td class=\"v\">")
                    .append(escape(v)).append("</td></tr>")
            }
            append("</table>")
        }
        for (n in s.notes) append("<p class=\"note\">").append(escape(n)).append("</p>")
        for (g in s.groups) section(g, (level + 1).coerceAtMost(4))
    }

    fun text(r: InventoryReport): String = buildString {
        appendLine(r.title)
        appendLine("Generated ${r.generatedAt} by YK Manager")
        for (s in r.sections) textSection(s, 0)
        appendLine()
        appendLine("This report lists configuration only. It contains no secrets, PINs or one-time codes.")
    }

    private fun StringBuilder.textSection(s: InvSection, depth: Int) {
        val pad = "  ".repeat(depth)
        appendLine()
        appendLine(if (depth == 0) "== ${s.title} ==" else "$pad${s.title}")
        for ((k, v) in s.rows) appendLine("$pad  $k: $v")
        for (n in s.notes) appendLine("$pad  ($n)")
        for (g in s.groups) textSection(g, depth + 1)
    }
}

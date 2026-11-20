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

    /**
     * Margin on every side of the printed page, in mils (1/1000 inch): 0.75 in.
     * Android's WebView print adapter ignores CSS @page margins and only uses the
     * margins in the print job's PrintAttributes, which the "Save as PDF" printer sets to
     * zero. The print code therefore forces these margins on the job (see MarginPrintAdapter),
     * and the stylesheet sets no page margin of its own so the two can't add up.
     */
    const val PRINT_MARGIN_MILS = 750

    private const val PRINT_CSS =
        "html{-webkit-print-color-adjust:exact;print-color-adjust:exact}" +
            "body{font-family:sans-serif;font-size:10pt;line-height:1.35;color:#111;margin:0}" +
            "@media screen{body{margin:16px}}" +
            "header{border-bottom:2pt solid #2e7d32;padding-bottom:6pt;margin-bottom:10pt}" +
            "h1{font-size:16pt;margin:0 0 2pt}p.meta{color:#555;font-size:9pt;margin:0}" +
            "h2{font-size:12pt;color:#1b5e20;border-bottom:1px solid #bbb;padding-bottom:2pt;" +
            "margin:16pt 0 6pt;page-break-after:avoid;break-after:avoid}" +
            "h3{font-size:10.5pt;margin:10pt 0 3pt;page-break-after:avoid;break-after:avoid}" +
            "h4{font-size:10pt;color:#333;margin:8pt 0 2pt;page-break-after:avoid;break-after:avoid}" +
            "section.g{margin-left:8pt;padding-left:8pt;border-left:2pt solid #e0e0e0}" +
            "table{border-collapse:collapse;width:100%;table-layout:fixed;margin:0 0 4pt}" +
            "tr{page-break-inside:avoid;break-inside:avoid}" +
            "tr:nth-child(even) td{background:#f5f5f5}" +
            "td{padding:3pt 6pt;vertical-align:top;border-bottom:0.5pt solid #e5e5e5}" +
            "td.k{width:32%;color:#444}" +
            "td.v{overflow-wrap:anywhere;word-break:break-word}" +
            "p.note{color:#555;font-style:italic;margin:3pt 0}" +
            "footer{margin-top:18pt;padding-top:6pt;border-top:0.5pt solid #ccc;color:#777;font-size:8pt;" +
            "page-break-inside:avoid;break-inside:avoid}"

    fun html(r: InventoryReport): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
        append(escape(r.title))
        append("</title><style>")
        append(PRINT_CSS)
        append("</style></head><body>")
        append("<header><h1>").append(escape(r.title)).append("</h1>")
        append("<p class=\"meta\">Generated ").append(escape(r.generatedAt)).append(" by YK Manager</p></header>")
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
        for (g in s.groups) {
            append("<section class=\"g\">")
            section(g, (level + 1).coerceAtMost(4))
            append("</section>")
        }
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

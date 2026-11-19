package net.skorchin.ykmanager.yubi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryTest {
    private val report = InventoryReport(
        "YubiKey inventory — YubiKey 5C NFC #123",
        "Nov 18, 2026",
        listOf(
            InvSection("Device", rows = listOf("Serial number" to "123")),
            InvSection(
                "Accounts (OATH)",
                rows = listOf("Accounts" to "1"),
                groups = listOf(InvSection("<script>alert(1)</script> — a&b\"c'", rows = listOf("Type" to "TOTP (30s)"))),
            ),
            InvSection("PIV", notes = listOf("Could not be read: timeout")),
        ),
    )

    @Test fun htmlEscapesUserControlledText() {
        val html = Inventory.html(report)
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; — a&amp;b&quot;c&#39;"))
        assertTrue(html.contains("<h3>"))
        assertTrue(html.contains("Could not be read: timeout"))
    }

    @Test fun textContainsAllSections() {
        val t = Inventory.text(report)
        assertTrue(t.contains("== Device =="))
        assertTrue(t.contains("  Serial number: 123"))
        assertTrue(t.contains("    Type: TOTP (30s)"))
        assertTrue(t.contains("(Could not be read: timeout)"))
    }
}

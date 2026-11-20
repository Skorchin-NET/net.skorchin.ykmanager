package net.skorchin.ykmanager.yubi

import com.yubico.yubikit.yubiotp.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpSlotStateTest {

    private val staticHistory = SlotHistory(SlotType.STATIC, 0L, mapOf("Length" to "12"))
    private val probe = Identification(SlotType.CHALRESP, Evidence.PROBE, listOf("Test response" to "ab…"))

    @Test fun identificationWinsOverHistoryAndHardware() {
        val v = OtpSlotState.slotView(Slot.ONE, configured = true, touch = false, hist = staticHistory, ident = probe)
        assertEquals(SlotType.CHALRESP, v.type)
        assertEquals(Evidence.PROBE, v.evidence)
        assertEquals(probe.details, v.details)
        assertEquals(staticHistory, v.history)
    }

    @Test fun consistentHistoryIsUsed() {
        val v = OtpSlotState.slotView(Slot.TWO, configured = true, touch = true, hist = staticHistory, ident = null)
        assertEquals(SlotType.STATIC, v.type)
        assertEquals(Evidence.HISTORY, v.evidence)
    }

    @Test fun historyContradictedByTheHardwareIsIgnored() {
        // A keyboard credential can't be on a slot the key reports as not touch-triggered.
        val v = OtpSlotState.slotView(Slot.ONE, configured = true, touch = false, hist = staticHistory, ident = null)
        assertEquals(SlotType.CHALRESP, v.type)
        assertEquals(Evidence.HARDWARE, v.evidence)
    }

    @Test fun hardwareOnly() {
        assertEquals(SlotType.KEYBOARD_UNKNOWN, OtpSlotState.slotView(Slot.ONE, true, true, null, null).type)
        val empty = OtpSlotState.slotView(Slot.ONE, configured = false, touch = null, hist = null, ident = null)
        assertNull(empty.type); assertNull(empty.evidence)
    }

    @Test fun consistency() {
        assertTrue(OtpSlotState.consistent(SlotType.HOTP, null))
        assertTrue(OtpSlotState.consistent(SlotType.YUBICO_OTP, true))
        assertFalse(OtpSlotState.consistent(SlotType.CHALRESP_OTHER, true))
        assertTrue(OtpSlotState.consistent(SlotType.CHALRESP_OTHER, false))
        assertFalse(OtpSlotState.consistent(SlotType.STATIC, false))
    }

    @Test fun outputPartNames() {
        assertEquals("OTP", SlotType.YUBICO_OTP.outputPart)
        assertEquals("code", SlotType.HOTP.outputPart)
        assertEquals("password", SlotType.STATIC.outputPart)
        assertEquals("output", SlotType.CHALRESP.outputPart)
    }

    @Test fun historyRoundTripsThroughJson() {
        assertEquals(staticHistory, SlotHistory.fromJson(staticHistory.toJson()))
        assertNull(SlotHistory.fromJson("not json"))
    }
}

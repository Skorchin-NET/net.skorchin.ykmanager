package net.skorchin.ykmanager.ui

import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinKeyboardTest {
    @Test fun pinFieldsOpenTheNumberKeypad() {
        assertEquals(KeyboardType.NumberPassword, pinKeyboardType(letters = false))
    }

    @Test fun lettersSwitchToTheFullPasswordKeyboard() {
        assertEquals(KeyboardType.Password, pinKeyboardType(letters = true))
    }

    @Test fun formFieldsAreNotPinsUnlessMarked() {
        assertFalse(Field.Text("pw", "Password", secret = true).pin)
        assertTrue(Field.Text("pin", "PIN", secret = true, pin = true).pin)
    }
}

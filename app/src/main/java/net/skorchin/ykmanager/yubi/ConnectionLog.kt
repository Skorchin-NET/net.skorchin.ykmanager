package net.skorchin.ykmanager.yubi

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short, in-memory record of how the app saw the YubiKey arrive: USB devices
 * present, permission state, NFC reader mode being held or released, taps that
 * came in and how. Nothing secret is recorded (no OTPs, PINs or NDEF payloads),
 * and nothing leaves the phone unless the user copies or shares it.
 */
object ConnectionLog {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(event: String) {
        val line = "${fmt.format(Date())}  $event"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() = synchronized(lines) { lines.clear() }
}

package net.skorchin.ykmanager.yubi

import com.yubico.yubikit.core.application.CommandException
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.UpdateConfiguration

/** Changing the output settings (Settings → OTP output) of an already-programmed slot. */
class OtpOutputUpdater(private val core: YubiKeyCore, private val state: OtpSlotState) {

    /**
     * Rewrites the updatable flags of a keyboard slot (Yubico OTP, HOTP, static) without touching
     * its credential (the Personalization Tool's "Update settings"). Needs firmware 2.3+ and a slot
     * that was programmed with "Allow update". The key cannot report the current flags, so every
     * updatable flag is set from [output]. Not offered for challenge-response slots: the firmware
     * merges the pacing bits of an update into the slot, and on those slots the same bits mean
     * "HMAC < 64 bytes" and "require touch".
     */
    fun updateOutput(slot: Slot, output: OtpOutputSettings, accessCodeHex: String) {
        core.run("update slot ${slot.num} settings") { device ->
            val serial0 = state.serial
            val known = state.history.get(serial0, slot)?.type
            if (known == SlotType.CHALRESP || known == SlotType.CHALRESP_OTHER) throw UserFacingException(
                "Slot ${slot.num} holds a challenge-response credential; its settings can only be changed by reprogramming it.")
            val acc = OtpCodec.parseAccessCode(accessCodeHex)
            val cfg = UpdateConfiguration().also { output.applyTo(it, keyboard = true) }
            try {
                core.otp(device) { it.updateConfiguration(slot, cfg, acc, acc) }
            } catch (e: UnsupportedOperationException) {
                throw UserFacingException("This YubiKey cannot update slot settings (firmware 2.3 or newer is needed, " +
                    "and every selected option must be supported).")
            } catch (e: CommandException) {
                throw UserFacingException("The YubiKey refused the update. The slot was probably programmed without " +
                    "\"Allow update\", or the access code is wrong. Reprogram the slot to change its settings.")
            }
            val serial = state.serial
            state.history.get(serial, slot)?.let { h ->
                val d = LinkedHashMap(h.details)
                d["Output"] = output.outputPattern(
                    firstPart = if (h.type == SlotType.YUBICO_OTP) "public ID" else null, secondPart = h.type.outputPart,
                )
                output.changesFromDefault().filterNot { it.startsWith("tab") || it.contains("Enter") }
                    .let { if (it.isEmpty()) d.remove("Options") else d["Options"] = it.joinToString() }
                state.history.put(serial, slot, h.copy(details = d))
            }
            core.toast("Slot ${slot.num} settings updated")
        }
    }
}

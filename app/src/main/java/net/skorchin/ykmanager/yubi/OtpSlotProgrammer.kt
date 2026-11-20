package net.skorchin.ykmanager.yubi

import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.yubiotp.HmacSha1SlotConfiguration
import com.yubico.yubikit.yubiotp.HotpSlotConfiguration
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.StaticPasswordSlotConfiguration
import com.yubico.yubikit.yubiotp.YubiOtpSession
import com.yubico.yubikit.yubiotp.YubiOtpSlotConfiguration

/** Writing OTP slots: programming credentials, deleting, swapping, and HMAC challenge-response. */
class OtpSlotProgrammer(private val core: YubiKeyCore, private val state: OtpSlotState) {

    /** Outcome of writing a slot; [secrets] holds generated values the user must save. */
    data class SlotResult(val type: SlotType, val details: Map<String, String>, val secrets: String?, val dialog: InfoDialog?)

    fun program(slot: Slot, program: SlotProgram, opts: SlotWriteOptions) {
        core.run("program slot ${slot.num}") { device ->
            val r = programOn(device, slot, program, opts, state.serial)
            r.dialog?.let(core::showDialog) ?: core.toast("Slot ${slot.num} programmed as ${r.type.label}")
        }
    }

    /** Program [slot] on an already-connected [device]. Shared by the slot screen and recipes. */
    suspend fun programOn(device: YubiKeyDevice, slot: Slot, program: SlotProgram, opts: SlotWriteOptions, serial: Int?): SlotResult {
        run {
            val curAcc = OtpCodec.parseAccessCode(opts.currentAccessCodeHex)
            val newAcc = OtpCodec.parseAccessCode(opts.newAccessCodeHex) ?: curAcc
            val details = linkedMapOf<String, String>()
            var dialog: InfoDialog? = null
            val out = opts.effectiveOutput

            val type: SlotType
            val write: (YubiOtpSession) -> Unit

            when (program) {
                is SlotProgram.YubicoOtp -> {
                    type = SlotType.YUBICO_OTP
                    val publicId = when {
                        program.useSerialAsPublicId -> {
                            val s = serial ?: throw UserFacingException(
                                "The serial number is not readable, so it cannot be used as the public ID."
                            )
                            OtpCodec.serialPublicId(s)
                        }
                        !program.publicIdModhex.isNullOrBlank() -> Encoding.fromModhex(program.publicIdModhex)
                        else -> ByteArray(6).also {
                            Encoding.randomBytes(5).copyInto(it, 1); it[0] = 0xff.toByte()
                        }
                    }
                    if (publicId.size > 16) throw UserFacingException("Public ID can be at most 16 bytes")
                    val privateId = program.privateIdHex?.takeIf { it.isNotBlank() }
                        ?.let(Encoding::fromHex) ?: Encoding.randomBytes(6)
                    val key = program.keyHex?.takeIf { it.isNotBlank() }
                        ?.let(Encoding::fromHex) ?: Encoding.randomBytes(16)
                    if (privateId.size != 6) throw UserFacingException("Private ID must be 6 bytes (12 hex digits)")
                    if (key.size != 16) throw UserFacingException("Secret key must be 16 bytes (32 hex digits)")
                    val pubMod = Encoding.toModhex(publicId)
                    details["Public ID"] = pubMod
                    write = { s ->
                        s.putConfiguration(
                            slot,
                            YubiOtpSlotConfiguration(publicId, privateId, key).also { out.applyTo(it, keyboard = true, yubicoOtp = true) },
                            newAcc, curAcc,
                        )
                    }
                    dialog = InfoDialog(
                        "Slot ${slot.num}: Yubico OTP programmed",
                        "Public ID: $pubMod\nPrivate ID: ${Encoding.toHex(privateId)}\n" +
                            "Secret key: ${Encoding.toHex(key)}\n\n" +
                            "These values are not stored by the app and cannot be read back from " +
                            "the YubiKey. Save them now if you will upload this credential to a " +
                            "validation server.",
                        copyValue = "${serial ?: ""},$pubMod,${Encoding.toHex(privateId)},${Encoding.toHex(key)}",
                    )
                }

                is SlotProgram.Hotp -> {
                    type = SlotType.HOTP
                    val secret = Encoding.fromBase32(program.secretBase32)
                    if (secret.size > 20) throw UserFacingException("HOTP secret must be at most 20 bytes")
                    details["Digits"] = if (program.digits8) "8" else "6"
                    write = { s ->
                        var c = HotpSlotConfiguration(secret).digits8(program.digits8).also { out.applyTo(it, keyboard = true) }
                        if (program.initialCounter > 0) c = c.imf(program.initialCounter)
                        s.putConfiguration(slot, c, newAcc, curAcc)
                    }
                }

                is SlotProgram.StaticPassword -> {
                    type = SlotType.STATIC
                    val pw = program.password?.takeIf { it.isNotEmpty() }
                        ?: if (program.modhexOnly) Encoding.randomModhex(program.generateLength)
                           else Encoding.randomPassword(program.generateLength)
                    if (pw.length > 38) throw UserFacingException("Static passwords can be at most 38 characters")
                    val codes = Encoding.passwordToScanCodes(pw)
                    details["Length"] = pw.length.toString()
                    write = { s ->
                        s.putConfiguration(
                            slot, StaticPasswordSlotConfiguration(codes).also { out.applyTo(it, keyboard = true) }, newAcc, curAcc
                        )
                    }
                    if (program.password.isNullOrEmpty()) {
                        dialog = InfoDialog(
                            "Slot ${slot.num}: static password programmed",
                            "Generated password: $pw\n\nThe app does not keep a copy.",
                            copyValue = pw,
                        )
                    }
                }

                is SlotProgram.ChallengeResponse -> {
                    type = SlotType.CHALRESP
                    val secret = program.secretHex?.takeIf { it.isNotBlank() }
                        ?.let(Encoding::fromHex) ?: Encoding.randomBytes(20)
                    if (secret.size > 64) throw UserFacingException("HMAC secret must be at most 64 bytes")
                    details["Require touch"] = if (program.requireTouch) "yes" else "no"
                    write = { s ->
                        s.putConfiguration(
                            slot,
                            HmacSha1SlotConfiguration(secret).requireTouch(program.requireTouch).lt64(true).also { out.applyTo(it, keyboard = false) },
                            newAcc, curAcc,
                        )
                    }
                    if (program.secretHex.isNullOrBlank()) {
                        dialog = InfoDialog(
                            "Slot ${slot.num}: challenge-response programmed",
                            "Generated secret: ${Encoding.toHex(secret)}\n\nThe app does not keep a copy.",
                            copyValue = Encoding.toHex(secret),
                        )
                    }
                }
            }

            try {
                core.otp(device) { write(it) }
            } catch (e: UnsupportedOperationException) {
                throw UserFacingException("This YubiKey's firmware does not support one of the selected OTP output settings " +
                    "(${out.changesFromDefault().joinToString().ifEmpty { "defaults" }}). Change them under Settings → OTP output.")
            }
            if (type != SlotType.CHALRESP) details["Output"] = out.outputPattern(
                firstPart = if (type == SlotType.YUBICO_OTP) "public ID" else null, secondPart = type.outputPart,
            )
            out.changesFromDefault().filterNot { it.startsWith("tab") || it.contains("Enter") }
                .takeIf { it.isNotEmpty() }?.let { details["Options"] = it.joinToString() }
            if (newAcc != null) details["Access code"] = "set"
            state.recordWrite(serial, slot, SlotHistory(type, System.currentTimeMillis(), details))
            return SlotResult(type, details, dialog?.copyValue, dialog)
        }
    }

    /** Delete [slot] on an already-connected [device]. */
    suspend fun deleteOn(device: YubiKeyDevice, slot: Slot, accessCodeHex: String, serial: Int?) {
        core.otp(device) { it.deleteConfiguration(slot, OtpCodec.parseAccessCode(accessCodeHex)) }
        state.recordWrite(serial, slot, null)
    }

    fun delete(slot: Slot, accessCodeHex: String) {
        core.run("delete slot ${slot.num}") { device ->
            core.otp(device) { it.deleteConfiguration(slot, OtpCodec.parseAccessCode(accessCodeHex)) }
            state.recordWrite(state.serial, slot, null)
            core.toast("Slot ${slot.num} deleted")
        }
    }

    fun deleteBoth() {
        core.run("delete both OTP slots") { device ->
            val serial = state.serial
            val config = core.otp(device) { it.configurationState }
            val failed = mutableListOf<Int>()
            for (slot in listOf(Slot.ONE, Slot.TWO)) {
                if (runCatching { config.isConfigured(slot) }.getOrDefault(true)) {
                    runCatching { core.otp(device) { it.deleteConfiguration(slot, null) } }
                        .onFailure { failed += slot.num }
                }
                if (slot.num !in failed) {
                    state.recordWrite(serial, slot, null)
                }
            }
            if (failed.isEmpty()) core.toast("Both OTP slots are now empty")
            else core.error("Could not delete slot ${failed.joinToString()} (protected by an access code?)")
        }
    }

    fun swap() {
        core.run("swap the slots") { device ->
            core.otp(device) { it.swapConfigurations() }
            state.swapSlots(state.serial)
            core.toast("Slots swapped")
        }
    }

    fun calculateHmac(slot: Slot, challengeHex: String) {
        core.run("calculate a response") { device ->
            val challenge = if (challengeHex.isBlank()) ByteArray(0) else Encoding.fromHex(challengeHex)
            if (challenge.size > 64) throw UserFacingException("Challenge can be at most 64 bytes")
            core.showTouchPrompt(true)
            val resp = core.otp(device) { it.calculateHmacSha1(slot, challenge, core.touchState()) }
            core.showTouchPrompt(false)
            core.showDialog(InfoDialog("HMAC-SHA1 response (slot ${slot.num})", Encoding.toHex(resp), Encoding.toHex(resp)))
        }
    }
}

package net.skorchin.ykmanager.yubi

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin

class QrDecodeTest {

    private val uri = "otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example"

    /** Render [uri] as a gray QR image, [module] px per module, with [margin] modules of quiet zone. */
    private fun render(module: Int, margin: Int): Triple<Int, Int, ByteArray> {
        val m = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to margin))
        val w = m.width * module
        val h = m.height * module
        val g = ByteArray(w * h) { i -> if (m.get((i % w) / module, (i / w) / module)) 0 else 0xFF.toByte() }
        return Triple(w, h, g)
    }

    @Test
    fun clean_code_decodes_after_smoothing() {
        val (w, h, g) = render(8, 4)
        assertEquals(uri, QrDecode.decodeSmoothed(w, h, g))
    }

    @Test
    fun code_cropped_to_its_edge_decodes_with_added_margin() {
        val (w, h, g) = render(8, 0)
        assertEquals(uri, QrDecode.decodeSmoothed(w, h, g))
    }

    @Test
    fun screen_photo_moire_is_smoothed_away() {
        val (w, h, g) = render(12, 0)
        // Mimic a camera photo of a monitor: a fine pixel grid plus a slanted interference pattern.
        val noisy = ByteArray(g.size) { i ->
            val x = i % w
            val y = i / w
            val base = g[i].toInt() and 0xFF
            var v = base * 0.75 + 32
            if (x % 3 == 0 || y % 3 == 0) v *= 0.55
            v += 40 * sin(2 * PI * (x * 0.31 + y * 0.17))
            v.toInt().coerceIn(0, 255).toByte()
        }
        assertEquals(uri, QrDecode.decodeSmoothed(w, h, noisy))
    }

    @Test
    fun box_blur_keeps_flat_areas_and_size() {
        val flat = ByteArray(50 * 40) { 200.toByte() }
        val out = QrDecode.boxBlur(flat, 50, 40, 3)
        assertEquals(flat.size, out.size)
        assertEquals(200, out[25 * 50 + 20].toInt() and 0xFF)
    }

    /** Optional: QR_FIXTURE=/path/to/photo.jpg checks a real failing photo (never committed). */
    @Test
    fun optional_real_photo_fixture() {
        val path = System.getenv("QR_FIXTURE")
        assumeTrue(path != null && File(path).exists())
        val img = ImageIO.read(File(path!!))
        val f = minOf(1.0, 1200.0 / maxOf(img.width, img.height))
        val w = (img.width * f).toInt()
        val h = (img.height * f).toInt()
        val scaled = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, w, h, null); dispose()
        }
        val text = QrDecode.decodeSmoothed(w, h, QrDecode.toGray(scaled.getRGB(0, 0, w, h, null, 0, w)))
        assertEquals(true, text?.startsWith("otpauth://"))
    }
}

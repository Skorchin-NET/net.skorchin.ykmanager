package net.skorchin.ykmanager.yubi

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/** Pure ZXing decoding helpers (no Android types), so they can be unit-tested on the JVM. */
internal object QrDecode {

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /** Try both binarizers on the image and on an inverted copy (light-on-dark codes). */
    fun decode(source: LuminanceSource): String? {
        for (src in listOf(source, source.invert())) {
            for (binarizer in listOf({ HybridBinarizer(src) }, { GlobalHistogramBinarizer(src) })) {
                val text = runCatching {
                    MultiFormatReader().apply { setHints(hints) }.decodeWithState(BinaryBitmap(binarizer())).text
                }.getOrNull()
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }

    fun decodeArgb(w: Int, h: Int, argb: IntArray): String? = decode(RGBLuminanceSource(w, h, argb))

    fun decodeGray(w: Int, h: Int, gray: ByteArray): String? =
        decode(PlanarYUVLuminanceSource(gray, w, h, 0, 0, w, h, false))

    /**
     * Blur at a few strengths (relative to the image size) and decode each,
     * plain and with a white margin. Smoothing removes the moiré a phone camera
     * picks up from a monitor's pixel grid, which otherwise defeats ZXing.
     */
    fun decodeSmoothed(w: Int, h: Int, gray: ByteArray): String? {
        val side = maxOf(w, h)
        val radii = listOf(maxOf(1, side / 400), maxOf(2, side / 250), maxOf(3, side / 150)).distinct()
        for (r in radii) {
            val blurred = boxBlur(gray, w, h, r)
            decodeGray(w, h, blurred)?.let { return it }
            val pad = maxOf(8, side / 15)
            decodeGray(w + 2 * pad, h + 2 * pad, padWhite(blurred, w, h, pad))?.let { return it }
        }
        return null
    }

    fun toGray(argb: IntArray): ByteArray = ByteArray(argb.size) { i ->
        val c = argb[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        ((r * 77 + g * 150 + b * 29) shr 8).toByte()
    }

    /** Separable box blur, applied twice (close to a Gaussian). Edges are clamped. */
    fun boxBlur(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        var cur = IntArray(src.size) { src[it].toInt() and 0xFF }
        repeat(2) {
            cur = blurPass(cur, w, h, r, horizontal = true)
            cur = blurPass(cur, w, h, r, horizontal = false)
        }
        return ByteArray(cur.size) { cur[it].toByte() }
    }

    private fun blurPass(src: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean): IntArray {
        val out = IntArray(src.size)
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) src[line * w + k] else src[k * w + line]
            }
            var sum = 0
            for (i in -r..r) sum += at(i)
            for (i in 0 until len) {
                val v = sum / div
                if (horizontal) out[line * w + i] = v else out[i * w + line] = v
                sum += at(i + r + 1) - at(i - r)
            }
        }
        return out
    }

    fun padWhite(src: ByteArray, w: Int, h: Int, pad: Int): ByteArray {
        val nw = w + 2 * pad
        val out = ByteArray(nw * (h + 2 * pad)) { 0xFF.toByte() }
        for (y in 0 until h) System.arraycopy(src, y * w, out, (y + pad) * nw + pad, w)
        return out
    }
}

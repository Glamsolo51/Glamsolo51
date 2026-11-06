package com.glamsolo.yetipizza

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

/** Decodes the QR matrices drawn by jeti-pizza/server/qr.js (fixtures written by its test) with ZXing. */
class QrDecodeTest {
    private fun decode(name: String): Pair<String, String> {
        val lines = javaClass.getResource("/qr/$name.txt")!!.readText().trimEnd('\n').split("\n")
        val expected = lines.first()
        val rows = lines.drop(1)
        val scale = 4
        val quiet = 4
        val n = rows.size + quiet * 2
        val side = n * scale
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == '1') for (dy in 0 until scale) for (dx in 0 until scale) {
                    pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0xFF000000.toInt()
                }
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        val result = QRCodeReader().decode(bitmap, mapOf(DecodeHintType.CHARACTER_SET to "UTF-8", DecodeHintType.TRY_HARDER to true))
        return expected to result.text
    }

    @Test fun shortText() = decode("short").let { (want, got) -> assertEquals(want, got) }
    @Test fun bankPayment() = decode("payment").let { (want, got) -> assertEquals(want, got) }
    @Test fun longWithVersionInfo() = decode("long").let { (want, got) -> assertEquals(want, got) }
}

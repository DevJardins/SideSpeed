package io.github.devjardins.sidespeed.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    @Test fun kofiQrIsSquareAndNonEmpty() {
        val first = qrMatrix(KOFI_URL)
        assertEquals(first.width, first.height)
        assertTrue(first.width > 0)
        var dark = 0
        for (x in 0 until first.width) {
            for (y in 0 until first.height) {
                if (first[x, y]) dark++
            }
        }
        assertTrue(dark > 0)
        // Deterministic: the same content encodes the same matrix.
        assertEquals(first.toString(), qrMatrix(KOFI_URL).toString())
    }
}

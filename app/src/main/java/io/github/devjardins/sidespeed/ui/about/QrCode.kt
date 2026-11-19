package io.github.devjardins.sidespeed.ui.about

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/** Support page linked (and QR-encoded) from the About screen. */
const val KOFI_URL = "https://ko-fi.com/devjardins"

/** QR matrix for [content]: square, quiet zone included. Pure JVM, covered by unit tests. */
fun qrMatrix(content: String, sizePx: Int = 512): BitMatrix =
    QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)

/** Encodes [content] once and renders it: dark modules on a white quiet zone. */
@Composable
fun rememberQrBitmap(content: String, sizePx: Int = 512): ImageBitmap =
    remember(content) {
        val matrix = qrMatrix(content, sizePx)
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bitmap.setPixel(
                        x,
                        y,
                        if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
                    )
                }
            }
        }.asImageBitmap()
    }

package io.github.devjardins.sidespeed.companion.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter

@Composable
internal actual fun rememberSsLogoPainter(): Painter {
    val bitmap = remember {
        val bytes = Base64.decode(SsLogoMarkBytes.BASE64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
    }
    return remember(bitmap) { BitmapPainter(bitmap) }
}

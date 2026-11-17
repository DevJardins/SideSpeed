package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap

@Composable
internal actual fun rememberSsLogoPainter(): Painter {
    val bitmap = remember {
        val bytes = SsLogoPainterKt.javaClass
            .getResourceAsStream("/ss_logo_mark.png")!!
            .readBytes()
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }
    return remember(bitmap) { BitmapPainter(bitmap) }
}

private object SsLogoPainterKt

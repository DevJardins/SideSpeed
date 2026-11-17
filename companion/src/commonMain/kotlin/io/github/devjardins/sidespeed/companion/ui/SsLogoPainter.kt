package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

/** The robot logo mark image for this platform (bundled PNG, same crop as the launcher icon). */
@Composable
internal expect fun rememberSsLogoPainter(): Painter

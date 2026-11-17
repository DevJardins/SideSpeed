package io.github.devjardins.sidespeed.companion.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Animator duration scale 0 means the user turned animations off system-wide. */
@Composable
actual fun isReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val scale = remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
    return scale == 0f
}

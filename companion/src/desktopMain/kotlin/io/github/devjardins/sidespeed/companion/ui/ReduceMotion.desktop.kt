package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.runtime.Composable

/** Desktop has no system reduce-motion signal wired: animations always run. */
@Composable
actual fun isReduceMotion(): Boolean = false

package io.github.devjardins.sidespeed.companion.ui

import androidx.compose.runtime.Composable

/** Whether the platform asks apps to reduce motion (no animation); desktop has no signal wired. */
@Composable
expect fun isReduceMotion(): Boolean

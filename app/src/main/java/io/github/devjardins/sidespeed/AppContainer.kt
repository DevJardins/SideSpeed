package io.github.devjardins.sidespeed

import android.content.Context
import io.github.devjardins.sidespeed.adb.AdbBackend
import io.github.devjardins.sidespeed.adb.LibAdbBackend

class AppContainer(context: Context) {
    val adb: AdbBackend = LibAdbBackend(context.applicationContext, context.filesDir)
}

package io.github.devjardins.sidespeed.companion.tv

import android.content.Context
import java.io.File

object AndroidDirs {
    /**
     * The ADB key directory, passed to [KadbTvConnector]. App-private, removed on uninstall, and under
     * noBackupFilesDir so Auto Backup never copies the private key off the device.
     */
    fun keyDir(context: Context): File = File(context.noBackupFilesDir, "adb")
}

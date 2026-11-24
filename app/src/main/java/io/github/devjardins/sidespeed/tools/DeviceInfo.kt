package io.github.devjardins.sidespeed.tools

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import io.github.devjardins.sidespeed.R
import io.github.devjardins.sidespeed.ui.text.UiText
import io.github.devjardins.sidespeed.ui.text.uiText
import java.util.Locale

data class StorageInfo(val freeBytes: Long, val totalBytes: Long)
data class MemoryInfo(val availBytes: Long, val totalBytes: Long)
data class DeviceSummary(val model: String, val androidVersion: String, val sdk: Int)

interface DeviceInfo {
    fun storage(): StorageInfo
    fun memory(): MemoryInfo
    fun summary(): DeviceSummary
}

/** Reads storage (StatFs on the data dir), RAM (ActivityManager) and Build fields. No helper needed. */
class AndroidDeviceInfo(private val context: Context) : DeviceInfo {
    override fun storage(): StorageInfo {
        val stat = StatFs(Environment.getDataDirectory().path)
        return StorageInfo(
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }

    override fun memory(): MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return MemoryInfo(availBytes = info.availMem, totalBytes = info.totalMem)
    }

    override fun summary(): DeviceSummary =
        DeviceSummary(model = Build.MODEL, androidVersion = Build.VERSION.RELEASE, sdk = Build.VERSION.SDK_INT)
}

/** 1000-based: whole MB below 1 GB, one decimal GB at or above. */
fun formatBytes(bytes: Long): UiText {
    val b = bytes.coerceAtLeast(0)
    if (b < 1_000_000_000) return uiText(R.string.tools_bytes_mb, UiText.Ltr((b / 1_000_000).toString()))
    return uiText(R.string.tools_bytes_gb, UiText.Ltr(String.format(Locale.US, "%.1f", b / 1_000_000_000.0)))
}

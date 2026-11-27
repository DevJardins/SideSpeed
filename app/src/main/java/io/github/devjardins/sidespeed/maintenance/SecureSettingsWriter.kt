package io.github.devjardins.sidespeed.maintenance

import android.content.ContentResolver
import android.provider.Settings

/**
 * The single write seam for `WRITE_SECURE_SETTINGS`. Parameterless so no caller can ever
 * write any setting other than `adb_wifi_enabled = 1`. Enforced by `SecureSettingsGuardTest`.
 */
fun interface SecureSettingsWriter {
    fun enableAdbWifi(): Boolean
}

class AndroidSecureSettingsWriter(private val resolver: ContentResolver) : SecureSettingsWriter {
    override fun enableAdbWifi(): Boolean = try {
        Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }
}

package io.github.devjardins.sidespeed

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import io.github.devjardins.sidespeed.adb.AdbBackend
import io.github.devjardins.sidespeed.adb.ConnectionStrategy
import io.github.devjardins.sidespeed.adb.HelperProbe
import io.github.devjardins.sidespeed.adb.LibAdbBackend
import io.github.devjardins.sidespeed.data.DataStorePrefs
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.helper.HelperClient
import java.net.Inet4Address

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val adb: AdbBackend = LibAdbBackend(appContext, appContext.filesDir)

    /** The single shared helper client: the helper writes one ack file, so two clients could fail each other's nonce check. */
    val helper: HelperClient = HelperClient()

    val prefs: Prefs = DataStorePrefs(appContext)

    val isFireOs: Boolean = Build.MANUFACTURER.equals("Amazon", ignoreCase = true) ||
        appContext.packageManager.hasSystemFeature("amazon.hardware.fire_tv")

    val strategy: ConnectionStrategy = ConnectionStrategy(
        helper = HelperProbe { helper.ping() },
        adb = adb,
        prefs = prefs,
        expectedApk = appContext.applicationInfo.sourceDir,
        isFireOs = isFireOs,
    )

    fun wifiIp(): String? = try {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { cm.getLinkProperties(it) }?.linkAddresses
            ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress
    } catch (_: Exception) {
        null
    }
}

package io.github.devjardins.sidespeed.maintenance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.github.devjardins.sidespeed.helper.TrustedBssids
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface BssidState {
    data class Found(val bssid: String) : BssidState
    data object LocationOff : BssidState
    data object NoPermission : BssidState
    data object NotOnWifi : BssidState
    data object Unknown : BssidState
}

fun interface BssidReader {
    suspend fun read(): BssidState
}

class AndroidBssidReader(private val context: Context) : BssidReader {
    override suspend fun read(): BssidState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return BssidState.NotOnWifi
        if (!hasWifiTransport(cm)) return BssidState.NotOnWifi

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locationOn = if (Build.VERSION.SDK_INT >= 28) {
            lm?.isLocationEnabled == true
        } else {
            true
        }
        if (!locationOn) return BssidState.LocationOff

        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) return BssidState.NoPermission

        val raw = if (Build.VERSION.SDK_INT >= 31) {
            readViaNetworkCallback(cm) ?: readViaWifiManager()
        } else {
            readViaWifiManager()
        }
        val normalized = TrustedBssids.normalizeBssid(raw)
        return if (normalized != null) BssidState.Found(normalized) else BssidState.Unknown
    }

    private fun hasWifiTransport(cm: ConnectivityManager): Boolean = try {
        val active = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (active != null && active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !active.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            true
        } else {
            @Suppress("DEPRECATION")
            cm.allNetworks.any { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }
    } catch (_: Exception) {
        false
    }

    @RequiresApi(31)
    private suspend fun readViaNetworkCallback(cm: ConnectivityManager): String? =
        withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                var unregistered = false
                val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        val bssid = (caps.transportInfo as? WifiInfo)?.bssid
                        if (cont.isActive) {
                            if (!unregistered) {
                                unregistered = true
                                runCatching { cm.unregisterNetworkCallback(this) }
                            }
                            cont.resume(bssid)
                        }
                    }
                }
                cont.invokeOnCancellation {
                    if (!unregistered) {
                        unregistered = true
                        runCatching { cm.unregisterNetworkCallback(callback) }
                    }
                }
                try {
                    cm.registerNetworkCallback(request, callback)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    @Suppress("DEPRECATION")
    private fun readViaWifiManager(): String? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.connectionInfo?.bssid
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val CALLBACK_TIMEOUT_MS = 3_000L

        fun isWifiConnected(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            return try {
                val active = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                if (active != null && active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !active.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    true
                } else {
                    @Suppress("DEPRECATION")
                    cm.allNetworks.any { net ->
                        val caps = cm.getNetworkCapabilities(net)
                        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    }
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}

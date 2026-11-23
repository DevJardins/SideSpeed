package io.github.devjardins.sidespeed.companion.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.Subnets

/**
 * LinkProperties reduced to plain strings, so the mapping can be tested without Android. The Wi-Fi name and
 * access point are raw as Android reports them (quoted SSID, redacted placeholders); [map] cleans them.
 */
data class LinkData(
    /** Default routes' gateways (a null entry is a default route with no gateway). */
    val defaultGateways: List<String?>,
    /** Link addresses as "address/prefix". */
    val linkAddresses: List<String>,
    val dnsServers: List<String>,
    val wifiSsid: String? = null,
    val wifiBssid: String? = null,
)

/** One network Android knows about, reduced to what picking the LAN needs. */
data class NetworkCandidate(
    val isVpn: Boolean,
    val isWifiOrEthernet: Boolean,
    val link: LinkData?,
    val isWifi: Boolean = false,
)

object FingerprintMapping {
    /**
     * Null when there is no usable IPv4 default route, or when the gateway isn't inside one of the link's IPv4
     * subnets (point-to-point links such as mobile data and tethers have none). Gateway MAC isn't read on Android.
     */
    fun map(data: LinkData?): NetworkFingerprint? {
        data ?: return null
        val subnets = data.linkAddresses.mapNotNull { Subnets.normalize(it) }
        for (gateway in data.defaultGateways) {
            if (gateway == null || gateway == "0.0.0.0" || Subnets.normalize("$gateway/32") == null) continue
            val subnet = subnets.firstOrNull { Subnets.containsHost(it, gateway) } ?: continue
            val own = data.linkAddresses.firstOrNull { Subnets.normalize(it) == subnet }?.substringBefore('/')
            // Scoped IPv6 link-local entries ("fe80::1%wlan0") change between joins; leave them out.
            return NetworkFingerprint(
                gateway, subnet, data.dnsServers.filter { '%' !in it }.toSet(),
                wifiSsid = cleanSsid(data.wifiSsid), wifiBssid = cleanBssid(data.wifiBssid), ownIp = own,
            )
        }
        return null
    }

    /**
     * The network to fingerprint. The active network wins unless it is a VPN: with Tailscale (or any VPN) up,
     * Android reports the VPN as active, and a VPN never maps to a fingerprint, so the Wi-Fi or Ethernet
     * network underneath is used instead. Mobile data is never picked.
     */
    fun pickLan(active: NetworkCandidate?, all: List<NetworkCandidate>): NetworkCandidate? {
        if (active != null && !active.isVpn) return active
        return all.firstOrNull { !it.isVpn && it.isWifiOrEthernet && map(it.link) != null }
    }

    /**
     * Whether the Wi-Fi name may be read for [picked]: the opt-in is on, location permission is granted,
     * location services are on (without them Android reports only redacted names), and the picked link is
     * Wi-Fi (an Ethernet link has no Wi-Fi name to read).
     */
    fun wifiNameAllowed(useWifiName: Boolean, permissionGranted: Boolean, locationOn: Boolean, picked: NetworkCandidate?): Boolean =
        useWifiName && permissionGranted && locationOn && picked?.isWifi == true

    /** "address/prefix" for an IPv4 host address; null for IPv6 or a missing address. */
    fun ipv4Cidr(hostAddress: String?, prefixLength: Int): String? =
        hostAddress?.takeIf { '.' in it && ':' !in it }?.let { "$it/$prefixLength" }

    /**
     * Android quotes the SSID (`"name"`) and reports `<unknown ssid>` without permission; the matcher needs
     * the exact name, so quotes are stripped here and redacted values become null.
     */
    fun cleanSsid(raw: String?): String? {
        if (raw == null) return null
        val s = raw.removeSurrounding("\"")
        return s.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }

    /** Android reports `02:00:00:00:00:00` without permission; normalised like a MAC, else null. */
    fun cleanBssid(raw: String?): String? {
        if (raw == null) return null
        return Subnets.normalizeMac(raw)?.takeIf { it != "02:00:00:00:00:00" }
    }
}

/**
 * [wifiNameEnabled] is the user's opt-in (Settings): the Wi-Fi name is read only when it is true,
 * [Manifest.permission.ACCESS_FINE_LOCATION] is granted, location services are on, and the picked
 * link is Wi-Fi (see [FingerprintMapping.wifiNameAllowed]).
 */
class AndroidFingerprintSource(
    private val app: Context,
    private val wifiNameEnabled: suspend () -> Boolean = { false },
) : FingerprintSource {
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)

    override suspend fun current(): NetworkFingerprint? {
        val active = cm.activeNetwork?.let(::candidate)
        @Suppress("DEPRECATION") // allNetworks is the only synchronous list; a callback-tracked set would add state.
        val all = cm.allNetworks.mapNotNull(::candidate)
        val picked = FingerprintMapping.pickLan(active, all)
        val allowed = FingerprintMapping.wifiNameAllowed(wifiNameEnabled(), permissionGranted(), locationOn(), picked)
        val name = if (allowed) wifiName() else null
        return FingerprintMapping.map(picked?.link?.copy(wifiSsid = name?.first, wifiBssid = name?.second))
    }

    private fun permissionGranted(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * The location services master switch: with it off Android reports only redacted Wi-Fi names.
     * API 28+ has [LocationManager.isLocationEnabled]; on 26–27 the secure setting is read instead.
     */
    private fun locationOn(): Boolean {
        val lm = app.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION") // LOCATION_MODE is the only read on API 26-27.
            Settings.Secure.getInt(app.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) !=
                Settings.Secure.LOCATION_MODE_OFF
        }
    }

    /**
     * The raw SSID and BSSID from [WifiManager.getConnectionInfo], or null when Wi-Fi is down. It is
     * deprecated but still returns the real values with fine location and location services on, on every
     * API level we support; the NetworkCapabilities transportInfo path redacts them on API 31+ and
     * misfires under VPNs, so it is not used. Callers gate this on [FingerprintMapping.wifiNameAllowed].
     */
    @Suppress("DEPRECATION") // connectionInfo is the only read that returns real values on every API level.
    private fun wifiName(): Pair<String?, String?>? {
        val info = wifi?.connectionInfo ?: return null
        return info.ssid to info.bssid
    }

    /** Null when the network vanished between calls (no capabilities). */
    private fun candidate(network: Network): NetworkCandidate? {
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        return NetworkCandidate(
            isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            isWifiOrEthernet = isWifi || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            link = linkData(cm.getLinkProperties(network)),
            isWifi = isWifi,
        )
    }

    companion object {
        fun linkData(lp: LinkProperties?): LinkData? {
            lp ?: return null
            return LinkData(
                defaultGateways = lp.routes.filter(RouteInfo::isDefaultRoute).map { it.gateway?.hostAddress },
                linkAddresses = lp.linkAddresses.mapNotNull { FingerprintMapping.ipv4Cidr(it.address.hostAddress, it.prefixLength) },
                dnsServers = lp.dnsServers.mapNotNull { it.hostAddress },
            )
        }
    }
}

package io.github.devjardins.sidespeed.companion.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.RouteInfo
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.Subnets

/** LinkProperties reduced to plain strings, so the mapping can be tested without Android. */
data class LinkData(
    /** Default routes' gateways (a null entry is a default route with no gateway). */
    val defaultGateways: List<String?>,
    /** Link addresses as "address/prefix". */
    val linkAddresses: List<String>,
    val dnsServers: List<String>,
)

object FingerprintMapping {
    /** Null when there is no IPv4 default route or no IPv4 address on the link. Gateway MAC is not read on Android. */
    fun map(data: LinkData?): NetworkFingerprint? {
        data ?: return null
        val gateway = data.defaultGateways.firstOrNull { it != null && Subnets.normalize("$it/32") != null } ?: return null
        val subnets = data.linkAddresses.mapNotNull { Subnets.normalize(it) }
        // Prefer the IPv4 address whose subnet holds the gateway.
        val subnet = subnets.firstOrNull { Subnets.containsHost(it, gateway) } ?: subnets.firstOrNull() ?: return null
        // Scoped IPv6 link-local entries ("fe80::1%wlan0") change between joins; leave them out.
        val dns = data.dnsServers.filter { '%' !in it }.toSet()
        return NetworkFingerprint(gateway, subnet, dns)
    }
}

class AndroidFingerprintSource(context: Context) : FingerprintSource {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    override suspend fun current(): NetworkFingerprint? = FingerprintMapping.map(linkData(cm.activeNetwork?.let(cm::getLinkProperties)))

    companion object {
        fun linkData(lp: LinkProperties?): LinkData? {
            lp ?: return null
            return LinkData(
                defaultGateways = lp.routes.filter(RouteInfo::isDefaultRoute).map { it.gateway?.hostAddress },
                linkAddresses = lp.linkAddresses.mapNotNull { la ->
                    la.address.hostAddress?.takeIf { '.' in it }?.let { "$it/${la.prefixLength}" }
                },
                dnsServers = lp.dnsServers.mapNotNull { it.hostAddress },
            )
        }
    }
}

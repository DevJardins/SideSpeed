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
            return NetworkFingerprint(gateway, subnet, data.dnsServers.filter { '%' !in it }.toSet(), ownIp = own)
        }
        return null
    }

    /** "address/prefix" for an IPv4 host address; null for IPv6 or a missing address. */
    fun ipv4Cidr(hostAddress: String?, prefixLength: Int): String? =
        hostAddress?.takeIf { '.' in it && ':' !in it }?.let { "$it/$prefixLength" }
}

class AndroidFingerprintSource(context: Context) : FingerprintSource {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    override suspend fun current(): NetworkFingerprint? = FingerprintMapping.map(linkData(cm.activeNetwork?.let(cm::getLinkProperties)))

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

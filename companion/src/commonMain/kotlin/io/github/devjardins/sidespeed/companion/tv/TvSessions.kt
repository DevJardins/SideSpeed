package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.companion.discovery.ConnectPortFinder
import io.github.devjardins.sidespeed.companion.discovery.TlsConnectService
import io.github.devjardins.sidespeed.companion.discovery.portForHost
import io.github.devjardins.sidespeed.companion.discovery.serviceForSerial
import io.github.devjardins.sidespeed.companion.net.AddressPolicy
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.store.CompanionStore
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvLink
import kotlinx.coroutines.CancellationException

/** How long a classic address that offered no encrypted connection stays remembered. */
private const val NO_ENCRYPTED_OFFER_MS = 10 * 60 * 1000L

/** A classic TV the user has not agreed to reach in cleartext. Nothing is sent. */
class ClassicNotAcknowledged : Exception("This TV has not been allowed to use the older connection")

/** The TV can do wireless debugging, so the cleartext connection is refused. */
class EncryptedAvailable : Exception("This TV can use an encrypted connection")

/** An encrypted TV's connect port is not known, and nothing is advertising one. */
class EncryptedPortUnknown : Exception("The encrypted port is not known")

/** What [TvSessions] needs in order to open a TV. [id] is the saved id, used when a new port is stored. */
data class TvTarget(val id: String, val address: String, val link: TvLink)

fun SavedTv.toTarget() = TvTarget(id, address, link)

/** True when [address] should be paired instead of reached in cleartext. */
fun interface LinkOffer {
    suspend fun encryptedAvailable(address: String): Boolean
}

/**
 * Opens a TV according to its [TvLink].
 *
 * An encrypted TV tries [TvLink.Encrypted.lastPort], then a service advertising that same address.
 * A port that works is saved with [CompanionStore.updateTv]. When both of those fail, the TV is
 * looked up by serial in the same advertisement. The new address and port are saved together only
 * when [relocateAllowed] is open, the host is a private IPv4 address, the connection works, and
 * [TvCommands.SERIAL] there equals the saved serial. A mismatch closes the session and saves nothing.
 * Port 5555 is never dialled for an encrypted TV.
 *
 * A classic TV with no acknowledgment is refused before any connection. An acknowledged classic TV
 * is refused when [classicTls] says port 5555 answered STLS, or when [ports] sees the host advertising
 * a TLS connect service. A check that finds neither is remembered for 10 minutes, so the next open
 * of that address does not probe or browse again. An encrypted offer is never remembered: the next
 * open checks it again.
 */
class TvSessions(
    private val connector: TvConnector,
    private val store: CompanionStore,
    private val classicTls: suspend (address: String) -> Boolean = { false },
    private val ports: ConnectPortFinder = ConnectPortFinder { null },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val relocateAllowed: suspend (TvTarget) -> Boolean = { false },
) {
    /** Address to the instant a remembered "no encrypted offer" result expires. A positive offer is never stored. */
    private val noEncryptedOfferUntil = mutableMapOf<String, Long>()

    suspend fun open(tv: SavedTv): TvSession = open(tv.toTarget())

    suspend fun open(target: TvTarget): TvSession = when (val link = target.link) {
        is TvLink.Classic -> openClassic(target, link)
        is TvLink.Encrypted -> openEncrypted(target, link)
    }

    private suspend fun openClassic(target: TvTarget, link: TvLink.Classic): TvSession {
        if (link.ackAtEpochMs == null) throw ClassicNotAcknowledged()
        if (!rememberedNoEncryptedOffer(target.address)) {
            val offered = classicTls(target.address) || ports.find(target.address) != null
            if (offered) throw EncryptedAvailable()
            noEncryptedOfferUntil[target.address] = clock() + NO_ENCRYPTED_OFFER_MS
        }
        return connector.open(target.address, TvConnector.DEFAULT_PORT)
    }

    /** True while a recent check found no encrypted offer for [address]. A positive result is never remembered. */
    private fun rememberedNoEncryptedOffer(address: String): Boolean {
        val until = noEncryptedOfferUntil[address] ?: return false
        return clock() < until
    }

    private suspend fun openEncrypted(target: TvTarget, link: TvLink.Encrypted): TvSession {
        val last = link.lastPort?.takeIf { usableEncryptedPort(it) }
        if (last != null) {
            try {
                return connector.open(target.address, last)
            } catch (e: TvUnreachable) {
                // The saved port is stale. Look up the one the TV is advertising now.
            }
        }
        val listed = ports.servicesFor(target.address)
        val found = if (listed != null) {
            portForHost(target.address, listed)?.takeIf { usableEncryptedPort(it) && it != last }
        } else {
            ports.find(target.address)?.takeIf { usableEncryptedPort(it) && it != last }
        }
        if (found != null) {
            val session = try {
                connector.open(target.address, found)
            } catch (e: TvUnreachable) {
                throw EncryptedPortUnknown()
            }
            store.updateTv(target.id) { current ->
                if (current.link is TvLink.Encrypted) current.copy(link = TvLink.Encrypted(found)) else current
            }
            return session
        }
        if (listed == null) throw EncryptedPortUnknown()
        return openMovedEncrypted(target, listed)
    }

    /**
     * The saved address is not advertising. Accept a different private host only when the caller's
     * gate is open and the device there reports the saved serial.
     */
    private suspend fun openMovedEncrypted(target: TvTarget, services: List<TlsConnectService>): TvSession {
        val saved = store.tvs().firstOrNull { it.id == target.id } ?: throw EncryptedPortUnknown()
        val match = serviceForSerial(saved.serial, services) ?: throw EncryptedPortUnknown()
        if (!usableEncryptedPort(match.port) || match.host == target.address) throw EncryptedPortUnknown()
        val ip = Ipv4.parse(match.host)
        if (ip == null || !AddressPolicy.isPrivate(ip)) throw EncryptedPortUnknown()
        if (!relocateAllowed(target)) throw EncryptedPortUnknown()
        val session = try {
            connector.open(match.host, match.port)
        } catch (e: TvUnreachable) {
            throw EncryptedPortUnknown()
        }
        val serial = try {
            session.run(TvCommands.SERIAL).trim()
        } catch (e: CancellationException) {
            session.close()
            throw e
        } catch (e: Exception) {
            session.close()
            throw e
        }
        if (serial.isEmpty() || serial != saved.serial) {
            session.close()
            throw EncryptedPortUnknown()
        }
        store.updateTv(target.id) { current ->
            if (current.link is TvLink.Encrypted && current.serial == saved.serial && current.address == target.address) {
                current.copy(address = match.host, link = TvLink.Encrypted(match.port))
            } else {
                current
            }
        }
        return session
    }

    private fun usableEncryptedPort(port: Int): Boolean =
        port in 1..65535 && port != TvConnector.DEFAULT_PORT
}

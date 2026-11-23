package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.companion.discovery.ConnectPortFinder
import io.github.devjardins.sidespeed.companion.store.CompanionStore
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvLink

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
 * An encrypted TV tries [TvLink.Encrypted.lastPort], then [ports]. A port that works is saved with
 * [CompanionStore.updateTv]. Port 5555 is never dialled for an encrypted TV.
 *
 * A classic TV with no acknowledgment is refused before any connection. An acknowledged classic TV
 * is refused when [classicTls] says port 5555 answered STLS, or when [ports] sees the host advertising
 * a TLS connect service.
 */
class TvSessions(
    private val connector: TvConnector,
    private val store: CompanionStore,
    private val classicTls: suspend (address: String) -> Boolean = { false },
    private val ports: ConnectPortFinder = ConnectPortFinder { null },
) {
    suspend fun open(tv: SavedTv): TvSession = open(tv.toTarget())

    suspend fun open(target: TvTarget): TvSession = when (val link = target.link) {
        is TvLink.Classic -> openClassic(target, link)
        is TvLink.Encrypted -> openEncrypted(target, link)
    }

    private suspend fun openClassic(target: TvTarget, link: TvLink.Classic): TvSession {
        if (link.ackAtEpochMs == null) throw ClassicNotAcknowledged()
        if (classicTls(target.address) || ports.find(target.address) != null) throw EncryptedAvailable()
        return connector.open(target.address, TvConnector.DEFAULT_PORT)
    }

    private suspend fun openEncrypted(target: TvTarget, link: TvLink.Encrypted): TvSession {
        val last = link.lastPort?.takeIf { it in 1..65535 && it != TvConnector.DEFAULT_PORT }
        if (last != null) {
            try {
                return connector.open(target.address, last)
            } catch (e: TvUnreachable) {
                // The saved port is stale. Look up the one the TV is advertising now.
            }
        }
        val found = ports.find(target.address)?.takeIf { it in 1..65535 && it != TvConnector.DEFAULT_PORT && it != last }
        if (found == null) throw EncryptedPortUnknown()
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
}

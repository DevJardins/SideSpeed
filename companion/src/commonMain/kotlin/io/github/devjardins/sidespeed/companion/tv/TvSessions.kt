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
import java.util.concurrent.ConcurrentHashMap

/** How long a classic address that offered no encrypted connection stays remembered. */
private const val NO_ENCRYPTED_OFFER_MS = 10 * 60 * 1000L

/** A classic TV the user has not agreed to reach in cleartext. Nothing is sent. */
class ClassicNotAcknowledged : Exception("This TV has not been allowed to use the older connection")

/** The TV can do wireless debugging, so the cleartext connection is refused. */
class EncryptedAvailable : Exception("This TV can use an encrypted connection")

/** An encrypted TV's connect port is not known, and nothing is advertising one. */
class EncryptedPortUnknown : Exception("The encrypted port is not known")

/** The peer completed ADB without TLS. The session is already closed, and nothing was sent. */
class TlsNotNegotiated : Exception("This connection did not use TLS")

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
 * Port 5555 is never dialled for an encrypted TV. A connection that did not finish TLS is closed
 * before any command, including the identity proof and the serial read.
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
    private val identity: TvIdentity? = null,
) {
    /** Address to the instant a remembered "no encrypted offer" result expires. A positive offer is never stored. */
    private val noEncryptedOfferUntil = ConcurrentHashMap<String, Long>()

    suspend fun open(tv: SavedTv, mayEnroll: Boolean = false): TvSession = open(tv.toTarget(), tv, mayEnroll)

    suspend fun open(target: TvTarget): TvSession = open(target, saved = null, mayEnroll = false)

    /**
     * A parked TV is refused here, before any connection, for every caller: upkeep, the activity poll,
     * and a user action that still has the saved id.
     * A saved TV with no secret is refused the same way unless [mayEnroll] is true: nothing is dialled.
     */
    private suspend fun open(target: TvTarget, saved: SavedTv?, mayEnroll: Boolean): TvSession {
        if (saved?.unproven == true || store.tvs().any { it.id == target.id && it.unproven }) throw TvUnproven()
        if (identity != null && saved != null && saved.identitySecret == null && !mayEnroll) throw NeedsConfirmation()
        return openLink(target, saved, mayEnroll)
    }

    private suspend fun openLink(target: TvTarget, saved: SavedTv?, mayEnroll: Boolean): TvSession = when (val link = target.link) {
        is TvLink.Classic -> openClassic(target, link, saved, mayEnroll)
        is TvLink.Encrypted -> openEncrypted(target, link, saved, mayEnroll)
    }

    private suspend fun openClassic(target: TvTarget, link: TvLink.Classic, saved: SavedTv?, mayEnroll: Boolean): TvSession {
        if (link.ackAtEpochMs == null) throw ClassicNotAcknowledged()
        if (!rememberedNoEncryptedOffer(target.address)) {
            val offered = classicTls(target.address) || ports.find(target.address) != null
            if (offered) throw EncryptedAvailable()
            noEncryptedOfferUntil[target.address] = clock() + NO_ENCRYPTED_OFFER_MS
        }
        return gate(connector.open(target.address, TvConnector.DEFAULT_PORT), saved, mayEnroll)
    }

    /** True while a recent check found no encrypted offer for [address]. A positive result is never remembered. */
    private fun rememberedNoEncryptedOffer(address: String): Boolean {
        val until = noEncryptedOfferUntil[address] ?: return false
        return clock() < until
    }

    private suspend fun openEncrypted(
        target: TvTarget,
        link: TvLink.Encrypted,
        saved: SavedTv?,
        mayEnroll: Boolean,
    ): TvSession {
        val last = link.lastPort?.takeIf { usableEncryptedPort(it) }
        if (last != null) {
            try {
                return gate(requireTls(connector.open(target.address, last)), saved, mayEnroll)
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
                requireTls(connector.open(target.address, found))
            } catch (e: TvUnreachable) {
                throw EncryptedPortUnknown()
            }
            store.updateTv(target.id) { current ->
                if (current.link is TvLink.Encrypted) current.copy(link = TvLink.Encrypted(found)) else current
            }
            return gate(session, saved, mayEnroll)
        }
        if (listed == null) throw EncryptedPortUnknown()
        return openMovedEncrypted(target, listed, saved, mayEnroll)
    }

    /**
     * The saved address is not advertising. Accept a different private host only when the caller's
     * gate is open and the device there reports the saved serial.
     */
    private suspend fun openMovedEncrypted(
        target: TvTarget,
        services: List<TlsConnectService>,
        hinted: SavedTv?,
        mayEnroll: Boolean,
    ): TvSession {
        val saved = hinted ?: store.tvs().firstOrNull { it.id == target.id } ?: throw EncryptedPortUnknown()
        val match = serviceForSerial(saved.serial, services) ?: throw EncryptedPortUnknown()
        if (!usableEncryptedPort(match.port) || match.host == target.address) throw EncryptedPortUnknown()
        val ip = Ipv4.parse(match.host)
        if (ip == null || !AddressPolicy.isPrivate(ip)) throw EncryptedPortUnknown()
        if (!relocateAllowed(target)) throw EncryptedPortUnknown()
        val session = try {
            requireTls(connector.open(match.host, match.port))
        } catch (e: TvUnreachable) {
            throw EncryptedPortUnknown()
        }
        val checker = identity
        // A TV that already has a secret proves it before the serial is read.
        if (checker != null && saved.identitySecret != null && !checker.prove(session, saved.identitySecret)) {
            session.close()
            throw TvUnproven()
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
        if (checker != null && saved.identitySecret == null) {
            if (!mayEnroll) {
                session.close()
                throw NeedsConfirmation()
            }
            if (saved.unproven) {
                session.close()
                throw TvUnproven()
            }
            val created = checker.enroll(session) ?: throw TvUnproven()
            store.updateTv(saved.id) { current ->
                if (current.identitySecret == null && current.serial == saved.serial) {
                    current.copy(identitySecret = created, unproven = false)
                } else {
                    current
                }
            }
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

    /**
     * Proves [saved] before the caller can send anything. No identity, or no saved TV (an add that has not
     * been written yet), leaves the session alone. A serial that does not match closes the session and
     * throws [TvWrongDevice]. The caller never receives that session.
     */
    private suspend fun gate(session: TvSession, saved: SavedTv?, mayEnroll: Boolean): TvSession {
        val checker = identity
        if (checker == null || saved == null) return session
        return when (val prepared = checker.prepare(session, saved, mayEnroll)) {
            is Prepared.Ready -> {
                if (prepared.enrolled) {
                    store.updateTv(saved.id) { current ->
                        if (current.identitySecret == null) {
                            current.copy(identitySecret = prepared.secret, unproven = false)
                        } else {
                            current
                        }
                    }
                }
                session
            }
            Prepared.WrongDevice -> {
                // prepare already closed it. Close again so a caller cannot keep a mismatched session
                // even if a future prepare forgets to.
                session.close()
                throw TvWrongDevice()
            }
            Prepared.NeedsConfirm -> throw NeedsConfirmation()
            Prepared.Failed -> throw TvUnproven()
        }
    }

    /**
     * Closes [session] and refuses it unless its transport finished TLS.
     * Checked again before each later command, so a transport Kadb replaced is not used.
     */
    private fun requireTls(session: TvSession): TvSession {
        if (!session.tlsNegotiated) {
            session.close()
            throw TlsNotNegotiated()
        }
        return TlsBoundSession(session)
    }

    private class TlsBoundSession(private val inner: TvSession) : TvSession {
        override val tlsNegotiated: Boolean get() = inner.tlsNegotiated

        override suspend fun run(command: String): String {
            if (!inner.tlsNegotiated) {
                inner.close()
                throw TlsNotNegotiated()
            }
            return inner.run(command)
        }

        override fun close() = inner.close()
    }

    private fun usableEncryptedPort(port: Int): Boolean =
        port in 1..65535 && port != TvConnector.DEFAULT_PORT
}

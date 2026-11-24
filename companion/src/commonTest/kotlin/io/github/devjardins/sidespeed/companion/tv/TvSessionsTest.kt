package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.companion.discovery.ConnectPortFinder
import io.github.devjardins.sidespeed.companion.discovery.TlsConnectService
import io.github.devjardins.sidespeed.companion.store.InMemoryCompanionStore
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvLink
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvSessionsTest {
    private val host = "192.168.5.20"

    private class Recording(var fail: Set<Int> = emptySet()) : TvConnector {
        val opens = mutableListOf<Pair<String, Int>>()
        override suspend fun open(address: String, port: Int): TvSession {
            opens += address to port
            if (port in fail) throw TvUnreachable(null)
            return FakeTvSession()
        }
    }

    private fun tv(link: TvLink) = SavedTv("tv-1", "Cube", host, "SER-1", "home", link = link)

    private fun sessions(
        connector: TvConnector,
        store: InMemoryCompanionStore,
        tls: Boolean = false,
        port: Int? = null,
    ): Pair<TvSessions, MutableList<String>> {
        val asked = mutableListOf<String>()
        val finder = ConnectPortFinder { host ->
            asked += host
            port
        }
        return TvSessions(connector, store, classicTls = { tls }, ports = finder) to asked
    }

    @Test fun encryptedUsesTheSavedPortAndDoesNotBrowse() = runBlocking {
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Encrypted(37_555))))
        val connector = Recording()
        val (sessions, asked) = sessions(connector, store, port = 1)
        sessions.open(store.tv("tv-1"))
        assertEquals(listOf(host to 37_555), connector.opens)
        assertTrue(asked.isEmpty())
        assertEquals(TvLink.Encrypted(37_555), store.tv("tv-1").link)
    }

    @Test fun encryptedBrowsesAfterTheSavedPortFailsAndSavesTheNewPort() = runBlocking {
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Encrypted(37_555))))
        val connector = Recording(fail = setOf(37_555))
        val (sessions, asked) = sessions(connector, store, port = 38_001)
        sessions.open(store.tv("tv-1"))
        assertEquals(listOf(host to 37_555, host to 38_001), connector.opens)
        assertEquals(listOf(host), asked)
        assertEquals(TvLink.Encrypted(38_001), store.tv("tv-1").link)
    }

    @Test fun encryptedNeverDials5555() = runBlocking {
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Encrypted(null))))
        val connector = Recording()
        val (sessions, _) = sessions(connector, store, port = null)
        assertFailsWith<EncryptedPortUnknown> { sessions.open(store.tv("tv-1")) }
        assertTrue(connector.opens.none { it.second == 5555 })
        assertTrue(connector.opens.isEmpty())

        val stored = InMemoryCompanionStore(listOf(tv(TvLink.Encrypted(5555))))
        val again = Recording()
        val (second, _) = sessions(again, stored, port = 36_000)
        second.open(stored.tv("tv-1"))
        assertEquals(listOf(host to 36_000), again.opens)
    }

    @Test fun encryptedWithNoPortThrowsAndSavesNothing() = runBlocking {
        val original = tv(TvLink.Encrypted(37_100))
        val store = InMemoryCompanionStore(listOf(original))
        val connector = Recording(fail = setOf(37_100))
        val (sessions, _) = sessions(connector, store, port = null)
        assertFailsWith<EncryptedPortUnknown> { sessions.open(original) }
        assertEquals(TvLink.Encrypted(37_100), store.tv("tv-1").link)
        assertEquals(listOf(host to 37_100), connector.opens)
    }

    @Test fun classicWithoutAnAcknowledgementOpensNothing() = runBlocking {
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Classic(null))))
        val connector = Recording()
        var probed = false
        var browsed = false
        val sessions = TvSessions(
            connector,
            store,
            classicTls = { probed = true; false },
            ports = ConnectPortFinder { browsed = true; 1 },
        )
        assertFailsWith<ClassicNotAcknowledged> { sessions.open(store.tv("tv-1")) }
        assertTrue(connector.opens.isEmpty())
        assertEquals(false, probed)
        assertEquals(false, browsed)
    }

    @Test fun acknowledgedClassicIsRefusedWhen5555AnswersTls() = runBlocking {
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Classic(1L))))
        val connector = Recording()
        val sessions = TvSessions(connector, store, classicTls = { true }, ports = ConnectPortFinder { null })
        assertFailsWith<EncryptedAvailable> { sessions.open(store.tv("tv-1")) }
        assertTrue(connector.opens.isEmpty())
    }

    @Test fun acknowledgedClassicIsRefusedWhenTheHostAdvertisesTls() = runBlocking {
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Classic(1L))))
        val connector = Recording()
        val sessions = TvSessions(connector, store, classicTls = { false }, ports = ConnectPortFinder { 37_000 })
        assertFailsWith<EncryptedAvailable> { sessions.open(store.tv("tv-1")) }
        assertTrue(connector.opens.isEmpty())
    }

    @Test fun aSecondClassicOpenWithinTenMinutesDoesNotProbeOrBrowse() = runBlocking {
        var now = 0L
        var probes = 0
        var browses = 0
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Classic(1L))))
        val connector = Recording()
        val sessions = TvSessions(
            connector,
            store,
            classicTls = { probes += 1; false },
            ports = ConnectPortFinder { browses += 1; null },
            clock = { now },
        )
        sessions.open(store.tv("tv-1"))
        now = 10 * 60 * 1000L - 1
        sessions.open(store.tv("tv-1"))
        assertEquals(1, probes)
        assertEquals(1, browses)
        assertEquals(listOf(host to 5555, host to 5555), connector.opens)
    }

    @Test fun aClassicOpenAfterTenMinutesProbesAgain() = runBlocking {
        var now = 0L
        var probes = 0
        var browses = 0
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Classic(1L))))
        val connector = Recording()
        val sessions = TvSessions(
            connector,
            store,
            classicTls = { probes += 1; false },
            ports = ConnectPortFinder { browses += 1; null },
            clock = { now },
        )
        sessions.open(store.tv("tv-1"))
        now = 10 * 60 * 1000L
        sessions.open(store.tv("tv-1"))
        assertEquals(2, probes)
        assertEquals(2, browses)
    }

    @Test fun aClassicTvThatAnswersTlsIsCheckedAgainNextTime() = runBlocking {
        var now = 0L
        var probes = 0
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Classic(1L))))
        val connector = Recording()
        val sessions = TvSessions(
            connector,
            store,
            classicTls = { probes += 1; true },
            ports = ConnectPortFinder { error("browsed a TV that already answered TLS") },
            clock = { now },
        )
        assertFailsWith<EncryptedAvailable> { sessions.open(store.tv("tv-1")) }
        now = 1
        assertFailsWith<EncryptedAvailable> { sessions.open(store.tv("tv-1")) }
        assertEquals(2, probes)
        assertTrue(connector.opens.isEmpty())
    }

    @Test fun anAdvertisedEncryptedPortIsCheckedAgainNextTime() = runBlocking {
        var now = 0L
        var probes = 0
        var browses = 0
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Classic(1L))))
        val connector = Recording()
        val sessions = TvSessions(
            connector,
            store,
            classicTls = { probes += 1; false },
            ports = ConnectPortFinder { browses += 1; 37_000 },
            clock = { now },
        )
        assertFailsWith<EncryptedAvailable> { sessions.open(store.tv("tv-1")) }
        now = 1
        assertFailsWith<EncryptedAvailable> { sessions.open(store.tv("tv-1")) }
        assertEquals(2, probes)
        assertEquals(2, browses)
        assertTrue(connector.opens.isEmpty())
    }

    @Test fun acknowledgedClassicOpens5555() = runBlocking {
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Classic(1L))))
        val connector = Recording()
        TvSessions(connector, store).open(store.tv("tv-1"))
        assertEquals(listOf(host to 5555), connector.opens)
    }

    @Test fun aCleartextAnswerOnAnEncryptedLinkGetsNoCommand() = runBlocking {
        val secret = "ab".repeat(32)
        val session = scripted(tls = false)
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Encrypted(37_555)).copy(identitySecret = secret)))
        assertFailsWith<TlsNotNegotiated> { openWithIdentity(session, store) }
        assertTrue(session.sent.isEmpty(), session.sent.joinToString())
        assertTrue(session.closed)
        assertEquals(secret, store.tv("tv-1").identitySecret)
        assertEquals(host, store.tv("tv-1").address)
        assertEquals(TvLink.Encrypted(37_555), store.tv("tv-1").link)
    }

    @Test fun pairingOverAnEncryptedPortThatIsNotTlsSendsNothing() = runBlocking {
        val session = scripted(tls = false)
        val store = InMemoryCompanionStore()
        val sessions = TvSessions(session.connector(), store, identity = identity())
        assertFailsWith<TlsNotNegotiated> {
            sessions.open(TvTarget("pending", host, TvLink.Encrypted(37_555)))
        }
        assertTrue(session.sent.isEmpty(), session.sent.joinToString())
        assertTrue(session.closed)
        assertTrue(store.tvs().isEmpty())
    }

    @Test fun aMovedEncryptedTvThatAnswersInCleartextIsNotRead() = runBlocking {
        val session = scripted(tls = false)
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Encrypted(37_000))))
        val connector = object : TvConnector {
            override suspend fun open(address: String, port: Int): TvSession {
                if (address == host) throw TvUnreachable(null)
                return session
            }
        }
        val services = listOf(TlsConnectService("192.168.5.77", 37_111, "adb-SER-1-bbbbbb"))
        assertFailsWith<TlsNotNegotiated> {
            TvSessions(connector, store, ports = namedPorts(services), relocateAllowed = { true }).open(store.tv("tv-1"))
        }
        assertTrue(session.sent.isEmpty(), session.sent.joinToString())
        assertTrue(session.closed)
        assertEquals(host, store.tv("tv-1").address)
        assertEquals(TvLink.Encrypted(37_000), store.tv("tv-1").link)
    }

    @Test fun anEncryptedLinkThatNegotiatedTlsStillProves() = runBlocking {
        val secret = "ab".repeat(32)
        val session = scripted(tls = true)
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Encrypted(37_555)).copy(identitySecret = secret)))
        openWithIdentity(session, store)
        assertEquals(listOf(proveCommand()), session.sent)
        assertFalse(session.closed)
    }

    @Test fun aClassicLinkDoesNotRequireTls() = runBlocking {
        val secret = "ab".repeat(32)
        val session = scripted(tls = false)
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Classic(1L)).copy(identitySecret = secret)))
        openWithIdentity(session, store)
        assertEquals(listOf(proveCommand()), session.sent)
        assertFalse(session.closed)
    }

    @Test fun aMovedEncryptedTvIsSavedWhenItsSerialMatches() = runBlocking {
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Encrypted(37_000))))
        val connector = ScriptedConnector(fail = setOf(host), serialAt = mapOf("192.168.5.77" to "SER-1"))
        val services = listOf(TlsConnectService("192.168.5.77", 37_111, "adb-SER-1-bbbbbb"))
        openMoved(connector, store, services, allow = true)
        assertEquals("192.168.5.77", store.tv("tv-1").address)
        assertEquals(TvLink.Encrypted(37_111), store.tv("tv-1").link)
        assertEquals(listOf(host to 37_000, "192.168.5.77" to 37_111), connector.opens)
        assertFalse(connector.sessions.single().closed)
    }

    @Test fun aMovedEncryptedTvWithTheWrongSerialSavesNothing() = runBlocking {
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Encrypted(37_000))))
        val connector = ScriptedConnector(fail = setOf(host), serialAt = mapOf("192.168.5.77" to "OTHER"))
        val services = listOf(TlsConnectService("192.168.5.77", 37_111, "adb-SER-1-bbbbbb"))
        assertFailsWith<EncryptedPortUnknown> { openMoved(connector, store, services, allow = true) }
        assertEquals(host, store.tv("tv-1").address)
        assertEquals(TvLink.Encrypted(37_000), store.tv("tv-1").link)
        assertEquals(listOf(TvCommands.SERIAL), connector.sessions.single().sent)
        assertTrue(connector.sessions.single().closed)
    }

    @Test fun aPublicHostAdvertisedForTheSerialIsIgnored() = runBlocking {
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Encrypted(37_000))))
        val connector = ScriptedConnector(fail = setOf(host), serialAt = mapOf("8.8.8.8" to "SER-1"))
        val services = listOf(TlsConnectService("8.8.8.8", 37_111, "adb-SER-1-bbbbbb"))
        assertFailsWith<EncryptedPortUnknown> { openMoved(connector, store, services, allow = true) }
        assertEquals(host, store.tv("tv-1").address)
        assertEquals(TvLink.Encrypted(37_000), store.tv("tv-1").link)
        assertEquals(listOf(host to 37_000), connector.opens)
    }

    @Test fun aMovedEncryptedTvIsNotSavedWhenTheGateIsClosed() = runBlocking {
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Encrypted(37_000))))
        val connector = ScriptedConnector(fail = setOf(host), serialAt = mapOf("192.168.5.77" to "SER-1"))
        val services = listOf(TlsConnectService("192.168.5.77", 37_111, "adb-SER-1-bbbbbb"))
        assertFailsWith<EncryptedPortUnknown> { openMoved(connector, store, services, allow = false) }
        assertEquals(host, store.tv("tv-1").address)
        assertEquals(TvLink.Encrypted(37_000), store.tv("tv-1").link)
        assertEquals(listOf(host to 37_000), connector.opens)
    }

    @Test fun severalAdvertisedServicesOpenOnlyTheOneNamedForThisSerial() = runBlocking {
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Encrypted(37_000))))
        val connector = ScriptedConnector(
            fail = setOf(host),
            serialAt = mapOf("192.168.5.77" to "SER-1", "192.168.5.88" to "SER-aaaaaa", "10.0.0.8" to "OTHER"),
        )
        val services = listOf(
            TlsConnectService("192.168.5.88", 38_001, "adb-SER-aaaaaa-cccccc"),
            TlsConnectService("10.0.0.8", 38_002, "adb-OTHER-aaaaaa"),
            TlsConnectService("192.168.5.77", 37_111, "adb-SER-1-bbbbbb"),
        )
        openMoved(connector, store, services, allow = true)
        assertEquals(listOf(host to 37_000, "192.168.5.77" to 37_111), connector.opens)
        assertEquals("192.168.5.77", store.tv("tv-1").address)
        assertEquals(TvLink.Encrypted(37_111), store.tv("tv-1").link)
    }

    private val companionId = "0123456789abcdef"
    private val nonce = "cd".repeat(32)

    private fun proveCommand() = TvCommands.proveSecret(companionId, nonce)

    private fun identity() = TvIdentity(companionId) {
        nonce.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun scripted(tls: Boolean): FakeTvSession {
        val secret = "ab".repeat(32)
        val prove = proveCommand()
        return FakeTvSession(
            mapOf(
                TvCommands.SERIAL to "SER-1",
                prove to "${TvProof.expected(secret, nonce, sha256 = true)}  -",
            ),
        ).also { it.tlsNegotiated = tls }
    }

    private suspend fun openWithIdentity(session: FakeTvSession, store: InMemoryCompanionStore) {
        TvSessions(session.connector(), store, identity = identity()).open(store.tv("tv-1"))
    }

    private class ScriptedConnector(
        private val fail: Set<String> = emptySet(),
        private val serialAt: Map<String, String> = emptyMap(),
    ) : TvConnector {
        val opens = mutableListOf<Pair<String, Int>>()
        val sessions = mutableListOf<FakeTvSession>()
        override suspend fun open(address: String, port: Int): TvSession {
            opens += address to port
            if (address in fail) throw TvUnreachable(null)
            val serial = serialAt[address] ?: throw TvUnreachable(null)
            val session = FakeTvSession(mapOf(TvCommands.SERIAL to serial))
            sessions += session
            return session
        }
    }

    private fun namedPorts(services: List<TlsConnectService>) = object : ConnectPortFinder {
        override suspend fun find(host: String) = services.firstOrNull { it.host == host }?.port
        override suspend fun servicesFor(host: String): List<TlsConnectService> = services
    }

    private suspend fun openMoved(
        connector: TvConnector,
        store: InMemoryCompanionStore,
        services: List<TlsConnectService>,
        allow: Boolean,
    ) {
        TvSessions(connector, store, ports = namedPorts(services), relocateAllowed = { allow })
            .open(store.tv("tv-1"))
    }
}

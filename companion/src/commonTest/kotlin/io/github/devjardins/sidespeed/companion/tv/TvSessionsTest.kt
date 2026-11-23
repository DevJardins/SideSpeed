package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.companion.discovery.ConnectPortFinder
import io.github.devjardins.sidespeed.companion.store.InMemoryCompanionStore
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvLink
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test fun acknowledgedClassicOpens5555() = runBlocking {
        val store = InMemoryCompanionStore(listOf(tv(TvLink.Classic(1L))))
        val connector = Recording()
        TvSessions(connector, store).open(store.tv("tv-1"))
        assertEquals(listOf(host to 5555), connector.opens)
    }
}

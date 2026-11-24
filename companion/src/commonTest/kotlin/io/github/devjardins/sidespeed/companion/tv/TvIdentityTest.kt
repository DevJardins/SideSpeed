package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.companion.store.InMemoryCompanionStore
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvLink
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvIdentityTest {
    private val companionId = "0123456789abcdef"
    private val secret = "ab".repeat(32)
    private val nonce = "cd".repeat(32)
    private val host = "192.168.5.20"

    private fun bytes(vararg hex: String): () -> ByteArray {
        val queue = ArrayDeque(hex.map { it.hexToBytes() })
        return { queue.removeFirst() }
    }

    private fun checker(vararg hex: String) = TvIdentity(companionId, bytes(*hex))

    private fun saved(secret: String? = this.secret, unproven: Boolean = false) = SavedTv(
        id = "tv-1", name = "Cube", address = host, serial = "SER-1", trustedNetworkId = "home",
        link = TvLink.Classic(ackAtEpochMs = 1L), identitySecret = secret, unproven = unproven,
    )

    private fun proveCommand() = TvCommands.proveSecret(companionId, nonce)

    private fun goodLine() = "${TvProof.expected(secret, nonce, sha256 = true)}  -"

    @Test fun proofRunsFirstAndAWrongAnswerSendsNothingElse() = runBlocking {
        val prove = proveCommand()
        val fake = FakeTvSession(mapOf(prove to goodLine(), TvCommands.SERIAL to "SER-1", TvCommands.STATUS_QUERY to "row"))
        val store = InMemoryCompanionStore(listOf(saved()))
        val session = TvSessions(fake.connector(), store, identity = checker(nonce)).open(store.tv("tv-1"))
        assertEquals(listOf(prove), fake.sent)
        assertEquals("SER-1", session.run(TvCommands.SERIAL))
        assertEquals(listOf(prove, TvCommands.SERIAL), fake.sent)

        val bad = FakeTvSession(mapOf(prove to "${"ff".repeat(32)}  -"))
        assertFailsWith<TvUnproven> {
            TvSessions(bad.connector(), store, identity = checker(nonce)).open(store.tv("tv-1"))
        }
        assertEquals(listOf(prove), bad.sent)
        assertTrue(bad.closed)
        assertFalse(TvCommands.SERIAL in bad.sent)
        assertFalse(TvCommands.STATUS_QUERY in bad.sent)
    }

    @Test fun missingFileAndCatErrorBothFailBeforeAnyOtherCommand() = runBlocking {
        val prove = proveCommand()
        val store = InMemoryCompanionStore(listOf(saved()))
        for (output in listOf("", "cat: /data/local/tmp/.sidespeed-id/$companionId: No such file or directory")) {
            val fake = FakeTvSession(mapOf(prove to output, TvCommands.SERIAL to "SER-1"))
            assertFailsWith<TvUnproven> {
                TvSessions(fake.connector(), store, identity = checker(nonce)).open(store.tv("tv-1"))
            }
            assertEquals(listOf(prove), fake.sent)
            assertTrue(fake.closed)
        }
    }

    @Test fun firstUseWritesThenProvesAndAFailedProofDoesNotContinue() = runBlocking {
        val write = TvCommands.writeSecret(companionId, secret)
        val prove = proveCommand()
        val fake = FakeTvSession(
            mapOf(
                TvCommands.SERIAL to "SER-1",
                write to "",
                prove to goodLine(),
                TvCommands.STATUS_QUERY to "row",
            ),
        )
        val store = InMemoryCompanionStore(listOf(saved(secret = null)))
        val session = TvSessions(fake.connector(), store, identity = checker(secret, nonce)).open(store.tv("tv-1"))
        assertEquals(listOf(TvCommands.SERIAL, write, prove), fake.sent)
        assertEquals(secret, store.tv("tv-1").identitySecret)
        assertEquals("row", session.run(TvCommands.STATUS_QUERY))

        val failed = FakeTvSession(mapOf(TvCommands.SERIAL to "SER-1", write to "", prove to ""))
        val again = InMemoryCompanionStore(listOf(saved(secret = null)))
        assertFailsWith<TvUnproven> {
            TvSessions(failed.connector(), again, identity = checker(secret, nonce)).open(again.tv("tv-1"))
        }
        assertEquals(listOf(TvCommands.SERIAL, write, prove), failed.sent)
        assertFalse(TvCommands.STATUS_QUERY in failed.sent)
        assertNull(again.tv("tv-1").identitySecret)
        assertTrue(failed.closed)
    }

    @Test fun aParkedTvIsNotOpened() = runBlocking {
        val fake = FakeTvSession(mapOf(proveCommand() to goodLine(), TvCommands.SERIAL to "SER-1"))
        val store = InMemoryCompanionStore(listOf(saved(unproven = true)))
        assertFailsWith<TvUnproven> {
            TvSessions(fake.connector(), store, identity = checker(nonce)).open(store.tv("tv-1"))
        }
        assertTrue(fake.opened.isEmpty())
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun firstUseDoesNotWriteWhenTheSerialDoesNotMatch() = runBlocking {
        val fake = FakeTvSession(mapOf(TvCommands.SERIAL to "OTHER"))
        val store = InMemoryCompanionStore(listOf(saved(secret = null)))
        assertFailsWith<TvWrongDevice> {
            TvSessions(fake.connector(), store, identity = checker(secret, nonce)).open(store.tv("tv-1"))
        }
        assertEquals(listOf(TvCommands.SERIAL), fake.sent)
        assertNull(store.tv("tv-1").identitySecret)
        assertTrue(fake.closed)
    }

    @Test fun aWrongFirstSerialClosesBeforeTheHelperReadsAgain() = runBlocking {
        val apk = "/data/app/x/base.apk"
        val fake = FakeTvSession(
            mapOf(
                TvCommands.SERIAL to "SER-1",
                TvCommands.PKG_PATH to "package:$apk",
                TvCommands.PIDOF to "123",
                TvCommands.cmdline(123) to "app_process\u0000--apk=$apk",
            ),
        )
        fake.nextAnswers[TvCommands.SERIAL] = ArrayDeque(listOf("OTHER"))
        val store = InMemoryCompanionStore(listOf(saved(secret = null)))
        assertFailsWith<TvWrongDevice> {
            TvSessions(fake.connector(), store, identity = checker(secret, nonce))
                .open(store.tv("tv-1"))
                .use { HelperKeeper().visit(it, "SER-1") }
        }
        assertEquals(listOf(TvCommands.SERIAL), fake.sent)
        assertTrue(fake.closed)
        assertNull(store.tv("tv-1").identitySecret)
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.SweepSource
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.store.InMemoryCompanionStore
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvLink
import io.github.devjardins.sidespeed.companion.tv.FakeTvSession
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvIdentity
import io.github.devjardins.sidespeed.companion.tv.TvProof
import io.github.devjardins.sidespeed.companion.upkeep.TcpScanner
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvIdentityViewModelTest {
    private val companionId = "0123456789abcdef"
    private val secret = "ab".repeat(32)
    private val nonce = "cd".repeat(32)
    private val home = NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1"))
    private val apk = "/data/app/x/base.apk"

    private fun bytes(): () -> ByteArray {
        var calls = 0
        return {
            calls += 1
            (if (calls == 1) secret else nonce).hexToBytes()
        }
    }

    private fun TestScope.model(fake: FakeTvSession, store: InMemoryCompanionStore = InMemoryCompanionStore(networks = listOf(TrustedNetwork("home", "Living room", home)))): CompanionViewModel {
        val identity = TvIdentity(companionId, bytes())
        val source = FingerprintSource { home }
        val upkeep = Upkeep(store, source, fake.connector(), HelperKeeper(), TcpScanner(), identity = identity) { 1_000_000L }
        return CompanionViewModel(
            store, source, upkeep, fake.connector(), Discovery(listOf(SweepSource), io.github.devjardins.sidespeed.companion.discovery.FakeLan()),
            this, { 1_000_000L }, { "tv-1" }, identity = identity,
        )
    }

    private fun scripted(prove: String): FakeTvSession {
        val write = TvCommands.writeSecret(companionId, secret)
        val proveCommand = TvCommands.proveSecret(companionId, nonce)
        return FakeTvSession(
            mapOf(
                TvCommands.SERIAL to "SER-1",
                TvCommands.MODEL to "Fire TV Cube",
                TvCommands.MANUFACTURER to "Amazon",
                write to "",
                proveCommand to prove,
                TvCommands.PKG_PATH to "package:$apk",
                TvCommands.PIDOF to "123",
                TvCommands.cmdline(123) to "app_process\u0000--apk=$apk",
                TvCommands.forgetSecret(companionId) to "",
            ),
        )
    }

    @Test fun addWritesThenProvesAndAFailedProofIsNotSaved() = runTest(UnconfinedTestDispatcher()) {
        val line = "${TvProof.expected(secret, nonce, sha256 = true)}  -"
        val ok = scripted(line)
        val store = InMemoryCompanionStore(networks = listOf(TrustedNetwork("home", "Living room", home)))
        val vm = model(ok, store)
        vm.addTvByAddress("192.168.5.20", acknowledged = true)
        assertEquals(secret, store.tvs().single().identitySecret)
        val writeAt = ok.sent.indexOf(TvCommands.writeSecret(companionId, secret))
        val proveAt = ok.sent.indexOf(TvCommands.proveSecret(companionId, nonce))
        assertTrue(writeAt >= 0 && proveAt == writeAt + 1)

        val bad = scripted("")
        val failedStore = InMemoryCompanionStore(networks = listOf(TrustedNetwork("home", "Living room", home)))
        val failed = model(bad, failedStore)
        failed.addTvByAddress("192.168.5.20", acknowledged = true)
        assertEquals(Copy.TV_CHECK_NOT_SAVED, failed.state.value.error)
        assertTrue(failedStore.tvs().isEmpty())
        assertTrue(TvCommands.STATUS_QUERY !in bad.sent)
    }

    @Test fun removeForgetsWhenTheTvAnswersAndStillRemovesWhenItDoesNot() = runTest(UnconfinedTestDispatcher()) {
        val fake = scripted("${TvProof.expected(secret, nonce, sha256 = true)}  -")
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv()),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(fake, store)
        vm.removeTv("tv-1")
        assertEquals(listOf(TvCommands.forgetSecret(companionId)), fake.sent.filter { it.startsWith("rm ") })
        assertTrue(store.tvs().isEmpty())

        val down = scripted("").also { it.failOnOpen = true }
        val kept = InMemoryCompanionStore(
            tvs = listOf(savedTv()),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        model(down, kept).removeTv("tv-1")
        assertTrue(down.sent.isEmpty())
        assertTrue(kept.tvs().isEmpty())
    }

    @Test fun anUnprovenTvShowsTheCardMessage() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv().copy(unproven = true)),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(scripted(""), store)
        assertEquals(Copy.TV_UNPROVEN, vm.state.value.tvs.single().note)
    }

    private fun savedTv() = SavedTv(
        id = "tv-1", name = "Cube", address = "192.168.5.20", serial = "SER-1", trustedNetworkId = "home",
        link = TvLink.Classic(ackAtEpochMs = 1L),
    )

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

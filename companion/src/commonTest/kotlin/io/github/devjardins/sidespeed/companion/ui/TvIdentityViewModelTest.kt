package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.discovery.ConnectPortFinder
import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.SweepSource
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.store.InMemoryCompanionStore
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvLink
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.FakeTvSession
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.PairCopy
import io.github.devjardins.sidespeed.companion.tv.PairResult
import io.github.devjardins.sidespeed.companion.tv.Pairer
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import io.github.devjardins.sidespeed.companion.tv.TvIdentity
import io.github.devjardins.sidespeed.companion.tv.TvProof
import io.github.devjardins.sidespeed.companion.upkeep.TcpScanner
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun TestScope.model(
        fake: FakeTvSession,
        store: InMemoryCompanionStore = InMemoryCompanionStore(networks = listOf(TrustedNetwork("home", "Living room", home))),
        connector: TvConnector = fake.connector(),
        pairer: Pairer = Pairer { _, _, _ -> PairResult.Failed(PairCopy.FAILED) },
        ports: ConnectPortFinder = ConnectPortFinder { null },
        fingerprint: NetworkFingerprint = home,
        random: () -> ByteArray = bytes(),
    ): CompanionViewModel {
        val identity = TvIdentity(companionId, random)
        val source = FingerprintSource { fingerprint }
        val upkeep = Upkeep(store, source, connector, HelperKeeper(), TcpScanner(), identity = identity, ports = ports) { 1_000_000L }
        return CompanionViewModel(
            store, source, upkeep, connector, Discovery(listOf(SweepSource), io.github.devjardins.sidespeed.companion.discovery.FakeLan()),
            this, { 1_000_000L }, { "tv-1" }, identity = identity, pairer = pairer, ports = ports,
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
        val line = "${TvProof.expected(secret, nonce, sha256 = true)}  -"
        val fake = scripted(line)
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv().copy(identitySecret = secret)),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(fake, store, random = { nonce.hexToBytes() })
        vm.removeTv("tv-1")
        val prove = TvCommands.proveSecret(companionId, nonce)
        val forget = TvCommands.forgetSecret(companionId)
        assertEquals(listOf(prove, forget), fake.sent)
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

    @Test fun removeOnAnotherNetworkSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val line = "${TvProof.expected(secret, nonce, sha256 = true)}  -"
        val fake = scripted(line)
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv().copy(identitySecret = secret)),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val away = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        val vm = model(fake, store, fingerprint = away, random = { nonce.hexToBytes() })
        fake.opened.clear()
        vm.removeTv("tv-1")
        assertTrue(fake.opened.isEmpty(), fake.opened.joinToString())
        assertTrue(fake.sent.isEmpty(), fake.sent.joinToString())
        assertTrue(store.tvs().isEmpty())
    }

    @Test fun aFailedProofSendsNoForget() = runTest(UnconfinedTestDispatcher()) {
        val fake = scripted("${"ab".repeat(32)}  -")
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv().copy(identitySecret = secret)),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(fake, store, random = { nonce.hexToBytes() })
        vm.removeTv("tv-1")
        assertEquals(listOf(TvCommands.proveSecret(companionId, nonce)), fake.sent)
        assertTrue(fake.closed)
        assertTrue(store.tvs().isEmpty())
    }

    @Test fun anEncryptedPortThatIsNotTlsIsNotRead() = runTest(UnconfinedTestDispatcher()) {
        val line = "${TvProof.expected(secret, nonce, sha256 = true)}  -"
        val fake = scripted(line).also { it.tlsNegotiated = false }
        val store = InMemoryCompanionStore(networks = listOf(TrustedNetwork("home", "Living room", home)))
        val vm = model(fake, store, random = { nonce.hexToBytes() })
        vm.startPairing("192.168.5.20")
        vm.submitConnectPort("37111")
        assertEquals(Copy.UNREACHABLE, vm.state.value.error)
        assertTrue(fake.sent.isEmpty(), fake.sent.joinToString())
        assertTrue(fake.closed)
        assertTrue(store.tvs().isEmpty())
    }

    @Test fun anUnprovenTvShowsTheCardMessage() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv().copy(unproven = true)),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(scripted(""), store)
        assertEquals(Copy.TV_UNPROVEN, vm.state.value.tvs.single().note)
    }

    @Test fun aParkedTvCardUsesAttentionStyleAndOnlyRemove() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryCompanionStore(
            tvs = listOf(
                savedTv().copy(unproven = true, status = TvStatus.RUNNING, identitySecret = secret),
                savedTv().copy(
                    id = "tv-2", serial = "SER-2", address = "192.168.5.21", name = "Stick",
                    unproven = true, status = TvStatus.NEEDS_ATTENTION, link = TvLink.Encrypted(37_000),
                ),
            ),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(scripted(""), store)
        val cards = vm.state.value.tvs
        assertEquals(listOf("tv-1", "tv-2"), cards.map { it.id })
        for (card in cards) {
            assertEquals("Stopped: check failed", card.statusLabel)
            assertEquals(StatusKind.WAITING, cardDot(card.status, card.checkableHere))
            assertEquals(Copy.TV_UNPROVEN, card.note)
            assertEquals(TvAttention.NONE, card.attention)
            assertEquals(listOf(TvCardAction.REMOVE), cardActions(card))
        }
        assertTrue(store.tvs().none { it.status == TvStatus.RUNNING })
    }

    @Test fun aParkedTvIsNotPolled() = runTest(UnconfinedTestDispatcher()) {
        val fake = scripted("")
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv().copy(unproven = true, status = TvStatus.RUNNING, identitySecret = secret)),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(fake, store)
        fake.opened.clear()
        vm.setHomeVisible(true)
        advanceTimeBy(10_000)
        assertTrue(fake.opened.isEmpty())
        assertTrue(fake.sent.none { it == TvCommands.STATUS_QUERY })
        vm.setHomeVisible(false)
    }

    @Test fun removeFinishesWhileForgetIsStillPending() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var started = false
        var cancelled = false
        val hanging = TvConnector { _, _ ->
            started = true
            try {
                gate.await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
            error("forget should still be pending")
        }
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv().copy(identitySecret = secret)),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(scripted(""), store, hanging)
        vm.removeTv("tv-1")
        assertTrue(started)
        assertFalse(gate.isCompleted)
        assertFalse(cancelled)
        assertTrue(store.tvs().isEmpty())
        assertTrue(vm.state.value.tvs.isEmpty())
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(cancelled)
    }

    @Test fun rePairingASavedTvProvesFirstAndKeepsTheOldSecret() = runTest(UnconfinedTestDispatcher()) {
        val line = "${TvProof.expected(secret, nonce, sha256 = true)}  -"
        val fake = scripted(line)
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv().copy(identitySecret = secret)),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(
            fake, store,
            pairer = Pairer { _, _, _ -> PairResult.Paired },
            ports = ConnectPortFinder { 37_123 },
            random = { nonce.hexToBytes() },
        )
        vm.pairSaved("tv-1")
        vm.submitPair("123456", "37000")
        val prove = TvCommands.proveSecret(companionId, nonce)
        val proveAt = fake.sent.indexOf(prove)
        val serialAt = fake.sent.indexOf(TvCommands.SERIAL)
        assertTrue(proveAt >= 0 && serialAt > proveAt, fake.sent.joinToString("\n"))
        assertTrue(fake.sent.none { it == TvCommands.writeSecret(companionId, secret) }, fake.sent.joinToString("\n"))
        assertEquals(secret, store.tv("tv-1").identitySecret)
        assertEquals(TvLink.Encrypted(37_123), store.tv("tv-1").link)
    }

    @Test fun aFailedRePairProofSendsNoSerialAndSavesNothing() = runTest(UnconfinedTestDispatcher()) {
        val fake = scripted("nope")
        val original = savedTv().copy(identitySecret = secret, link = TvLink.Encrypted(37_000), status = TvStatus.RUNNING)
        val store = InMemoryCompanionStore(
            tvs = listOf(original),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(
            fake, store,
            pairer = Pairer { _, _, _ -> PairResult.Paired },
            ports = ConnectPortFinder { 37_123 },
            random = { nonce.hexToBytes() },
        )
        vm.pairSaved("tv-1")
        vm.submitPair("123456", "37000")
        assertEquals(listOf(TvCommands.proveSecret(companionId, nonce)), fake.sent)
        val saved = store.tv("tv-1")
        assertEquals(secret, saved.identitySecret)
        assertEquals(original.link, saved.link)
        assertEquals(original.address, saved.address)
        assertEquals(true, saved.unproven)
        assertEquals(TvStatus.CHECK_FAILED, saved.status)
        val card = vm.state.value.tvs.single()
        assertEquals("Stopped: check failed", card.statusLabel)
        assertEquals(Copy.TV_UNPROVEN, card.note)
        assertEquals(true, fake.closed)
    }

    @Test fun aLegacyTvEnrollsOnlyAfterItsSerialMatches() = runTest(UnconfinedTestDispatcher()) {
        val line = "${TvProof.expected(secret, nonce, sha256 = true)}  -"
        val fake = scripted(line)
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv()),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(
            fake, store,
            pairer = Pairer { _, _, _ -> PairResult.Paired },
            ports = ConnectPortFinder { 37_123 },
        )
        vm.pairSaved("tv-1")
        vm.submitPair("123456", "37000")
        val write = TvCommands.writeSecret(companionId, secret)
        val serialAt = fake.sent.indexOf(TvCommands.SERIAL)
        val writeAt = fake.sent.indexOf(write)
        val modelAt = fake.sent.indexOf(TvCommands.MODEL)
        assertTrue(serialAt >= 0 && writeAt > serialAt && modelAt > writeAt, fake.sent.joinToString("\n"))
        assertEquals(1, fake.sent.count { it == write })
        assertEquals(secret, store.tv("tv-1").identitySecret)
        assertEquals(TvLink.Encrypted(37_123), store.tv("tv-1").link)

        val mismatch = scripted(line).also { it.outputs[TvCommands.SERIAL] = "OTHER" }
        val kept = savedTv()
        val mismatchStore = InMemoryCompanionStore(
            tvs = listOf(kept),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val mismatchVm = model(
            mismatch, mismatchStore,
            pairer = Pairer { _, _, _ -> PairResult.Paired },
            ports = ConnectPortFinder { 37_123 },
        )
        mismatchVm.pairSaved("tv-1")
        mismatchVm.submitPair("123456", "37000")
        assertTrue(mismatch.sent.none { it == write }, mismatch.sent.joinToString("\n"))
        assertEquals(null, mismatchStore.tv("tv-1").identitySecret)
        assertEquals(kept.link, mismatchStore.tv("tv-1").link)
        assertEquals(kept.address, mismatchStore.tv("tv-1").address)
        assertEquals(Copy.DIFFERENT_DEVICE, mismatchVm.state.value.error)
    }

    @Test fun aRePairWithAWrongFirstSerialShowsDifferentDeviceAndSavesNothing() = runTest(UnconfinedTestDispatcher()) {
        val line = "${TvProof.expected(secret, nonce, sha256 = true)}  -"
        val mismatch = scripted(line)
        mismatch.nextAnswers[TvCommands.SERIAL] = ArrayDeque(listOf("OTHER"))
        val kept = savedTv()
        val mismatchStore = InMemoryCompanionStore(
            tvs = listOf(kept),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val mismatchVm = model(
            mismatch, mismatchStore,
            pairer = Pairer { _, _, _ -> PairResult.Paired },
            ports = ConnectPortFinder { 37_123 },
        )
        mismatchVm.pairSaved("tv-1")
        mismatchVm.submitPair("123456", "37000")
        assertEquals(listOf(TvCommands.SERIAL), mismatch.sent)
        assertTrue(mismatch.closed)
        assertEquals(null, mismatchStore.tv("tv-1").identitySecret)
        assertEquals(kept.link, mismatchStore.tv("tv-1").link)
        assertEquals(kept.address, mismatchStore.tv("tv-1").address)
        assertEquals(false, mismatchStore.tv("tv-1").unproven)
        assertEquals(Copy.DIFFERENT_DEVICE, mismatchVm.state.value.error)
    }

    @Test fun aPollSendsNothingAfterAMismatchedFirstSerial() = runTest(UnconfinedTestDispatcher()) {
        val fake = scripted("${TvProof.expected(secret, nonce, sha256 = true)}  -")
        fake.nextAnswers[TvCommands.SERIAL] = ArrayDeque(listOf("OTHER"))
        fake.outputs[TvCommands.STATUS_QUERY] = "Row: 0 v=1, state=compiling, pkg=com.example.app, label=Example, mode=speed, started_ms=1, queue=0, last_pkg=, last_label=, last_result=, last_ms=0, self=optimized, version=0.2.1"
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv().copy(status = TvStatus.RUNNING)),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(fake, store)
        vm.setHomeVisible(true)
        runCurrent()
        assertEquals(listOf(TvCommands.SERIAL), fake.sent)
        assertTrue(fake.closed)
        assertEquals(TvStatus.RUNNING, store.tv("tv-1").status)
        assertEquals(false, store.tv("tv-1").unproven)
        assertEquals(null, store.tv("tv-1").identitySecret)
        vm.setHomeVisible(false)
    }

    @Test fun anActionOnAParkedTvSendsNoCommand() = runTest(UnconfinedTestDispatcher()) {
        val fake = scripted("${TvProof.expected(secret, nonce, sha256 = true)}  -")
        val store = InMemoryCompanionStore(
            tvs = listOf(
                savedTv().copy(
                    unproven = true, identitySecret = secret, status = TvStatus.RUNNING,
                    link = TvLink.Encrypted(37_000),
                ),
            ),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(
            fake, store,
            pairer = Pairer { _, _, _ -> PairResult.Paired },
            ports = ConnectPortFinder { 37_123 },
        )
        fake.opened.clear()
        vm.restartNow("tv-1")
        vm.acknowledgeClassic("tv-1")
        vm.useTypedPort("tv-1", "37123")
        vm.pairSaved("tv-1")
        vm.submitPair("123456", "37000")
        vm.submitConnectPort("41000")
        vm.onOpen()
        assertTrue(fake.opened.isEmpty(), fake.opened.joinToString())
        assertTrue(fake.sent.isEmpty(), fake.sent.joinToString())
    }

    private fun savedTv() = SavedTv(
        id = "tv-1", name = "Cube", address = "192.168.5.20", serial = "SER-1", trustedNetworkId = "home",
        link = TvLink.Classic(ackAtEpochMs = 1L),
    )

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.discovery.ConnectPortFinder
import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.SweepSource
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.store.InMemoryCompanionStore
import io.github.devjardins.sidespeed.companion.store.Reach
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
import kotlin.test.assertNull
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

    @Test fun openingAndPollingDoNotContactASavedTvThatHasNoSecret() = runTest(UnconfinedTestDispatcher()) {
        val line = "${TvProof.expected(secret, nonce, sha256 = true)}  -"
        val fake = scripted(line)
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv().copy(status = TvStatus.RUNNING)),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(fake, store)
        fake.opened.clear()
        vm.refreshNow()
        vm.setHomeVisible(true)
        advanceTimeBy(10_000)
        vm.setHomeVisible(false)
        vm.onOpen()
        assertTrue(fake.sent.isEmpty(), fake.sent.joinToString())
        assertTrue(fake.opened.isEmpty() || fake.closed)
        assertEquals(null, store.tv("tv-1").identitySecret)
        assertFalse(store.tv("tv-1").unproven)
        assertTrue(fake.sent.none { it == TvCommands.STATUS_QUERY || it == TvCommands.SERIAL }, fake.sent.joinToString())
    }

    @Test fun confirmOnHomeEnrollsWhenTheSerialMatches() = runTest(UnconfinedTestDispatcher()) {
        val line = "${TvProof.expected(secret, nonce, sha256 = true)}  -"
        val fake = scripted(line)
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv()),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(fake, store)
        assertEquals(TvAttention.CONFIRM, vm.state.value.tvs.single().attention)
        assertEquals("Confirm this is your TV before SideSpeed uses it.", Copy.CONFIRM_TV)
        assertEquals("Confirm", Copy.CONFIRM)
        vm.confirmTv("tv-1")
        val write = TvCommands.writeSecret(companionId, secret)
        val prove = TvCommands.proveSecret(companionId, nonce)
        val serialAt = fake.sent.indexOf(TvCommands.SERIAL)
        val writeAt = fake.sent.indexOf(write)
        val proveAt = fake.sent.indexOf(prove)
        assertTrue(serialAt >= 0 && writeAt > serialAt && proveAt > writeAt, fake.sent.joinToString("\n"))
        assertEquals(secret, store.tv("tv-1").identitySecret)
        assertFalse(store.tv("tv-1").unproven)
        assertEquals(TvAttention.NONE, vm.state.value.tvs.single().attention)
    }

    @Test fun confirmOnHomeWritesNothingWhenTheSerialDiffers() = runTest(UnconfinedTestDispatcher()) {
        val line = "${TvProof.expected(secret, nonce, sha256 = true)}  -"
        val fake = scripted(line).also { it.outputs[TvCommands.SERIAL] = "OTHER" }
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv()),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(fake, store)
        vm.confirmTv("tv-1")
        val write = TvCommands.writeSecret(companionId, secret)
        assertTrue(fake.sent.none { it == write }, fake.sent.joinToString("\n"))
        assertEquals(null, store.tv("tv-1").identitySecret)
        assertEquals(TvStatus.WRONG_DEVICE, store.tv("tv-1").status)
        assertFalse(store.tv("tv-1").unproven)
        assertTrue(fake.closed)
    }

    @Test fun classicAckEnrollsOnceAndDoesNotAskAgain() = runTest(UnconfinedTestDispatcher()) {
        val line = "${TvProof.expected(secret, nonce, sha256 = true)}  -"
        val fake = scripted(line)
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv().copy(link = TvLink.Classic(ackAtEpochMs = null))),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(fake, store)
        assertEquals(TvAttention.LEGACY_ACK, vm.state.value.tvs.single().attention)
        vm.acknowledgeClassic("tv-1")
        val write = TvCommands.writeSecret(companionId, secret)
        assertEquals(1, fake.sent.count { it == write })
        assertEquals(secret, store.tv("tv-1").identitySecret)
        assertEquals(TvAttention.NONE, vm.state.value.tvs.single().attention)
        val writes = fake.sent.count { it == write }
        vm.onOpen()
        assertEquals(writes, fake.sent.count { it == write })
        assertEquals(TvAttention.NONE, vm.state.value.tvs.single().attention)
    }

    @Test fun aTvWithASecretIsStillProvedBeforeThePoll() = runTest(UnconfinedTestDispatcher()) {
        val line = "${TvProof.expected(secret, nonce, sha256 = true)}  -"
        val fake = scripted(line).also { it.outputs[TvCommands.STATUS_QUERY] = "" }
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv().copy(identitySecret = secret, status = TvStatus.RUNNING)),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(fake, store, random = { nonce.hexToBytes() })
        fake.opened.clear()
        vm.setHomeVisible(true)
        advanceTimeBy(1_000)
        val prove = TvCommands.proveSecret(companionId, nonce)
        assertEquals(prove, fake.sent.firstOrNull())
        assertTrue(fake.sent.indexOf(prove) < fake.sent.indexOf(TvCommands.SERIAL))
        vm.setHomeVisible(false)
    }

    @Test fun anUnprovenTvShowsTheCardMessage() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryCompanionStore(
            tvs = listOf(savedTv().copy(unproven = true)),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(scripted(""), store)
        assertEquals(Copy.TV_UNPROVEN, vm.state.value.tvs.single().note)
    }

    @Test fun aParkedTvCardIsRedAndOnlyOffersRemove() = runTest(UnconfinedTestDispatcher()) {
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
            assertEquals(Glow.RED, cardGlow(card.reach, card.checkableHere, card.attention, parked = true))
            assertEquals(StatusKind.BAD, cardDot(card.status, card.checkableHere, card.attention, parked = true))
            assertEquals(Copy.TV_UNPROVEN, card.note)
            assertEquals(TvAttention.NONE, card.attention)
            assertEquals(listOf(TvCardAction.REMOVE), cardActions(card))
        }
        assertTrue(store.tvs().none { it.status == TvStatus.RUNNING })
    }

    @Test fun aTvWaitingForTrustSaysWaitingForYou() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryCompanionStore(
            tvs = listOf(
                savedTv().copy(status = TvStatus.NEEDS_ATTENTION, reach = Reach.UNREACHABLE, lastCheckedEpochMs = 0L),
                savedTv().copy(
                    id = "tv-2", serial = "SER-2", address = "192.168.5.21", name = "Stick",
                    link = TvLink.Classic(null),
                    status = TvStatus.NEEDS_ATTENTION, reach = Reach.UNREACHABLE, lastCheckedEpochMs = 0L,
                ),
                savedTv().copy(
                    id = "tv-3", serial = "SER-3", address = "192.168.5.22", name = "Tube",
                    identitySecret = secret, link = TvLink.Classic(1L),
                    status = TvStatus.NEEDS_ATTENTION, reach = Reach.UNREACHABLE, lastCheckedEpochMs = 0L,
                ),
            ),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(scripted(""), store)
        val cards = vm.state.value.tvs
        assertEquals(
            listOf(TvAttention.CONFIRM, TvAttention.LEGACY_ACK, TvAttention.LEGACY_PAIR),
            cards.map { it.attention },
        )
        for (card in cards) {
            val line = cardCheckedLine(card.attention, card.checkableHere, card.checkedAtEpochMs, nowMs = 60_000L)
            assertEquals(Copy.WAITING_FOR_YOU, line, card.id)
            assertFalse(line!!.contains("Checked"), line)
            assertEquals(Glow.ORANGE, cardGlow(card.reach, card.checkableHere, card.attention), card.id)
            assertEquals(StatusKind.TRUST, cardDot(card.status, card.checkableHere, card.attention), card.id)
        }
    }

    @Test fun wirelessOffStaysAConnectionProblem() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryCompanionStore(
            tvs = listOf(
                savedTv().copy(
                    identitySecret = secret,
                    link = TvLink.Encrypted(null),
                    status = TvStatus.NEEDS_ATTENTION,
                    reach = Reach.UNREACHABLE,
                    lastCheckedEpochMs = 0L,
                ),
            ),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val card = model(scripted(""), store).state.value.tvs.single()
        assertEquals(TvAttention.WIRELESS_OFF, card.attention)
        assertEquals(Glow.RED, cardGlow(card.reach, card.checkableHere, card.attention))
        assertEquals(StatusKind.WAITING, cardDot(card.status, card.checkableHere, card.attention))
        assertEquals("Checked 1 min ago", cardCheckedLine(card.attention, card.checkableHere, card.checkedAtEpochMs, 60_000L))
    }

    @Test fun trustOffThisNetworkKeepsTheLastSeenCard() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryCompanionStore(
            tvs = listOf(
                savedTv().copy(
                    trustedNetworkId = "away",
                    status = TvStatus.NEEDS_ATTENTION,
                    reach = Reach.UNREACHABLE,
                    lastCheckedEpochMs = 0L,
                ),
            ),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val card = model(scripted(""), store).state.value.tvs.single()
        assertEquals(TvAttention.CONFIRM, card.attention)
        assertEquals(false, card.checkableHere)
        assertNull(cardGlow(card.reach, card.checkableHere, card.attention))
        assertEquals(StatusKind.UNKNOWN, cardDot(card.status, card.checkableHere, card.attention))
        assertEquals("Checked 1 min ago", cardCheckedLine(card.attention, card.checkableHere, card.checkedAtEpochMs, 60_000L))
    }

    @Test fun confirmThenASuccessfulCheckIsGreenAndRunning() = runTest(UnconfinedTestDispatcher()) {
        val line = "${TvProof.expected(secret, nonce, sha256 = true)}  -"
        val store = InMemoryCompanionStore(
            tvs = listOf(
                savedTv().copy(status = TvStatus.NEEDS_ATTENTION, reach = Reach.UNREACHABLE, lastCheckedEpochMs = 1L),
            ),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(scripted(line), store)
        assertEquals(TvAttention.CONFIRM, vm.state.value.tvs.single().attention)
        vm.confirmTv("tv-1")
        val card = vm.state.value.tvs.single()
        assertEquals(TvAttention.NONE, card.attention)
        assertEquals(TvStatus.RUNNING, card.status)
        assertEquals(Reach.REACHED_LAN, card.reach)
        assertEquals(Reach.REACHED_LAN, store.tv("tv-1").reach)
        assertEquals(Glow.GREEN, cardGlow(card.reach, card.checkableHere, card.attention))
        assertEquals(StatusKind.GOOD, cardDot(card.status, card.checkableHere, card.attention))
    }

    @Test fun acknowledgeThenASuccessfulCheckIsGreenAndRunning() = runTest(UnconfinedTestDispatcher()) {
        val line = "${TvProof.expected(secret, nonce, sha256 = true)}  -"
        val store = InMemoryCompanionStore(
            tvs = listOf(
                savedTv().copy(
                    link = TvLink.Classic(null),
                    status = TvStatus.NEEDS_ATTENTION,
                    reach = Reach.UNREACHABLE,
                    lastCheckedEpochMs = 1L,
                ),
            ),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val vm = model(scripted(line), store)
        assertEquals(TvAttention.LEGACY_ACK, vm.state.value.tvs.single().attention)
        vm.acknowledgeClassic("tv-1")
        val card = vm.state.value.tvs.single()
        assertEquals(TvAttention.NONE, card.attention)
        assertEquals(TvStatus.RUNNING, card.status)
        assertEquals(Reach.REACHED_LAN, card.reach)
        assertEquals(Reach.REACHED_LAN, store.tv("tv-1").reach)
        assertEquals(Glow.GREEN, cardGlow(card.reach, card.checkableHere, card.attention))
        assertEquals(StatusKind.GOOD, cardDot(card.status, card.checkableHere, card.attention))
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

    @Test fun aPollDoesNotContactATvThatStillNeedsConfirming() = runTest(UnconfinedTestDispatcher()) {
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
        assertTrue(fake.sent.isEmpty(), fake.sent.joinToString())
        assertTrue(fake.opened.isEmpty())
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

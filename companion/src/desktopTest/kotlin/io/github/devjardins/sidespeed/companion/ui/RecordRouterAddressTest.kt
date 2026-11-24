package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.ProbeResult
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.store.JsonFileCompanionStore
import io.github.devjardins.sidespeed.companion.tv.FakeTvSession
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.upkeep.TcpScanner
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A Trusted Network saved before the router's hardware address was known must gain it
 * once the desktop app is on that network and can read the address.
 */
class RecordRouterAddressTest {
    private val home = NetworkFingerprint("192.168.4.1", "192.168.4.0/22", setOf("1.1.1.1", "8.8.8.8"))
    private val mac = "6c:bf:2f:26:4f:12"

    @Test fun openingTheAppRecordsTheRouterAddressInTheFile() = runBlocking {
        val dir = Files.createTempDirectory("sidespeed-router").toFile()
        try {
            val store = JsonFileCompanionStore(dir)
            store.saveTrustedNetwork(TrustedNetwork("home", "Home Network", home))
            val source = FingerprintSource { home.copy(gatewayMac = mac, ownIp = "192.168.4.23") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val fake = FakeTvSession()
            val vm = CompanionViewModel(
                store,
                source,
                Upkeep(store, source, fake.connector(), HelperKeeper(), TcpScanner()) { 1_000_000L },
                fake.connector(),
                Discovery(emptyList(), { _: Ipv4, _: Int, _: Int -> ProbeResult.Timeout }),
                scope,
                { 1_000_000L },
                { "tv-1" },
            )
            vm.onOpen()
            withTimeout(10_000) { awaitIdle(scope) }
            val text = File(dir, JsonFileCompanionStore.FILE_NAME).readText()
            assertEquals(mac, JsonFileCompanionStore(dir).trustedNetworks().single().fingerprint.gatewayMac, text)
            scope.coroutineContext[Job]!!.cancel()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theHomeScreenRecordsTheRouterAddressWhenStartupMissedIt() = runBlocking {
        val dir = Files.createTempDirectory("sidespeed-router").toFile()
        try {
            val store = JsonFileCompanionStore(dir)
            store.saveTrustedNetwork(TrustedNetwork("home", "Home Network", home))
            var publishMac = false
            val source = FingerprintSource {
                home.copy(gatewayMac = if (publishMac) mac else null, ownIp = "192.168.4.23")
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val fake = FakeTvSession()
            val vm = viewModel(store, source, scope, fake)
            vm.onOpen()
            withTimeout(10_000) { awaitIdle(scope) }
            assertEquals(null, JsonFileCompanionStore(dir).trustedNetworks().single().fingerprint.gatewayMac)
            publishMac = true
            vm.setHomeVisible(true)
            withTimeout(5_000) {
                while (JsonFileCompanionStore(dir).trustedNetworks().single().fingerprint.gatewayMac != mac) delay(20)
            }
            val text = File(dir, JsonFileCompanionStore.FILE_NAME).readText()
            assertEquals(mac, JsonFileCompanionStore(dir).trustedNetworks().single().fingerprint.gatewayMac, text)
            scope.coroutineContext[Job]!!.cancel()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun aNewerFileDoesNotGainARouterAddress() = runBlocking {
        val dir = Files.createTempDirectory("sidespeed-router").toFile()
        try {
            val original = """{"version":5,"trustedNetworks":[{"id":"home","name":"Home Network","fingerprint":{"gatewayIp":"192.168.4.1","subnet":"192.168.4.0/22","dnsServers":["1.1.1.1","8.8.8.8"],"gatewayMac":null}}],"settings":{}}"""
            File(dir, JsonFileCompanionStore.FILE_NAME).writeText(original)
            val store = JsonFileCompanionStore(dir)
            val source = FingerprintSource { home.copy(gatewayMac = mac, ownIp = "192.168.4.23") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val vm = viewModel(store, source, scope, FakeTvSession())
            vm.onOpen()
            withTimeout(10_000) { awaitIdle(scope) }
            vm.setHomeVisible(true)
            delay(300)
            assertEquals(original, File(dir, JsonFileCompanionStore.FILE_NAME).readText())
            scope.coroutineContext[Job]!!.cancel()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun viewModel(
        store: JsonFileCompanionStore,
        source: FingerprintSource,
        scope: CoroutineScope,
        fake: FakeTvSession,
    ) = CompanionViewModel(
        store,
        source,
        Upkeep(store, source, fake.connector(), HelperKeeper(), TcpScanner()) { 1_000_000L },
        fake.connector(),
        Discovery(emptyList(), { _: Ipv4, _: Int, _: Int -> ProbeResult.Timeout }),
        scope,
        { 1_000_000L },
        { "tv-1" },
    )

    private suspend fun awaitIdle(scope: CoroutineScope) {
        delay(50)
        while (true) {
            val active = scope.coroutineContext[Job]!!.children.filter { it.isActive }.toList()
            if (active.isEmpty()) return
            active.forEach { it.join() }
        }
    }
}

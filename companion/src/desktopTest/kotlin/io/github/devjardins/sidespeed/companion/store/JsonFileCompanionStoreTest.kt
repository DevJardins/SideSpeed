package io.github.devjardins.sidespeed.companion.store

import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonFileCompanionStoreTest {
    private val dir: File = Files.createTempDirectory("sidespeed-store").toFile()
    private val file get() = File(dir, JsonFileCompanionStore.FILE_NAME)

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    private val tv = SavedTv(
        id = "tv1", name = "Living \"room\" \\ TV\né\u0001", address = "192.168.5.50", serial = "G070VM1234",
        trustedNetworkId = "net1", status = TvStatus.WAITING_FOR_ALLOW, lastCheckedEpochMs = 1_700_000_000_123L,
        offlineCount = 2, lastRefindEpochMs = null, skipBackgroundUntilUserAction = true,
    )
    private val network = TrustedNetwork(
        "net1", "Trusted Network 1",
        NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1", "1.1.1.1"), "aa:bb:cc:dd:ee:ff"),
    )

    @Test fun missingFileGivesDefaults() = runBlocking {
        val s = JsonFileCompanionStore(dir)
        assertEquals(emptyList(), s.tvs())
        assertEquals(emptyList(), s.trustedNetworks())
        assertEquals(CompanionSettings(), s.settings())
        assertEquals(false, CompanionSettings().notifications)
        assertEquals(15, CompanionSettings().intervalMinutes)
        assertEquals(false, CompanionSettings().startAtLogin)
    }

    @Test fun roundTripsAcrossInstances() = runBlocking {
        val s = JsonFileCompanionStore(dir)
        s.saveTv(tv)
        s.saveTv(tv.copy(id = "tv2", serial = "S2", lastRefindEpochMs = 5L, status = TvStatus.OFFLINE))
        s.saveTrustedNetwork(network)
        s.saveTrustedNetwork(network.copy(id = "net2", fingerprint = network.fingerprint.copy(gatewayMac = null, dnsServers = emptySet())))
        s.saveSettings(CompanionSettings(notifications = true, intervalMinutes = 60, startAtLogin = true))

        val r = JsonFileCompanionStore(dir)
        assertEquals(s.tvs(), r.tvs())
        assertEquals(tv, r.tvs().first())
        assertEquals(s.trustedNetworks(), r.trustedNetworks())
        assertEquals(CompanionSettings(true, 60, true), r.settings())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun renamedAndClearedLabelsSurviveRestart() = runBlocking {
        val s = JsonFileCompanionStore(dir)
        s.saveTrustedNetwork(network.copy(name = "Cabin"))
        s.saveTrustedNetwork(network.copy(id = "net2", name = ""))
        val r = JsonFileCompanionStore(dir).trustedNetworks()
        assertEquals(listOf("Cabin", "net2"), r.map { it.displayName })
    }

    @Test fun isFireTvRoundTripsAndDefaultsToTrue() = runBlocking {
        val s = JsonFileCompanionStore(dir)
        s.saveTv(tv.copy(isFireTv = false))
        assertEquals(false, JsonFileCompanionStore(dir).tvs().single().isFireTv)
        assertEquals(true, SavedTv("a", "n", "1.2.3.4", "S", "net").isFireTv)
    }

    @Test fun saveReplacesByIdAndRemoveDeletes() = runBlocking {
        val s = JsonFileCompanionStore(dir)
        s.saveTv(tv)
        s.saveTv(tv.copy(address = "192.168.5.77"))
        assertEquals(listOf("192.168.5.77"), JsonFileCompanionStore(dir).tvs().map { it.address })
        s.removeTv("tv1")
        s.saveTrustedNetwork(network)
        s.removeTrustedNetwork("net1")
        val r = JsonFileCompanionStore(dir)
        assertEquals(emptyList(), r.tvs())
        assertEquals(emptyList(), r.trustedNetworks())
    }

    @Test fun blankSerialIsRefused() = runBlocking<Unit> {
        val s = JsonFileCompanionStore(dir)
        assertFailsWith<IllegalArgumentException> { s.saveTv(tv.copy(serial = "  ")) }
        assertEquals(emptyList(), s.tvs())
    }

    @Test fun corruptFileGivesDefaultsAndIsKeptUntilNextSave() = runBlocking {
        file.writeText("{ this is not json")
        val s = JsonFileCompanionStore(dir)
        assertEquals(emptyList(), s.tvs())
        assertEquals(CompanionSettings(), s.settings())
        assertEquals("{ this is not json", file.readText(), "reading never rewrites a corrupt file")

        s.saveSettings(CompanionSettings(intervalMinutes = 60))
        assertEquals(60, JsonFileCompanionStore(dir).settings().intervalMinutes)
        assertEquals("{ this is not json", File(dir, JsonFileCompanionStore.FILE_NAME + ".corrupt").readText())
    }

    @Test fun wrongShapeGivesDefaults() = runBlocking {
        file.writeText("[1, 2, 3]")
        assertEquals(emptyList(), JsonFileCompanionStore(dir).tvs())
    }

    @Test fun badEntriesAreSkippedAndUnknownStatusIsUnknown() = runBlocking {
        file.writeText(
            """
            {"version":1,"tvs":[
              {"id":"a","name":"A","address":"1.2.3.4","serial":"S","trustedNetworkId":"n","status":"SOMETHING_NEW"},
              {"id":"b","name":"B","address":"1.2.3.5","serial":"","trustedNetworkId":"n"},
              {"id":"c","name":"C"}
            ],"settings":{"intervalMinutes":60}}
            """.trimIndent(),
        )
        val s = JsonFileCompanionStore(dir)
        assertEquals(listOf("a"), s.tvs().map { it.id })
        assertEquals(TvStatus.UNKNOWN, s.tvs().single().status)
        assertEquals(CompanionSettings(intervalMinutes = 60), s.settings())
    }

    @Test fun updateTvTransformsTheCurrentCopyOnly() = runBlocking {
        val s = JsonFileCompanionStore(dir)
        s.saveTv(tv)
        s.saveTv(tv.copy(name = "Renamed"))
        assertTrue(s.updateTv("tv1") { it.copy(status = TvStatus.RUNNING, offlineCount = 0) })
        val r = JsonFileCompanionStore(dir).tvs().single()
        assertEquals("Renamed", r.name)
        assertEquals(TvStatus.RUNNING, r.status)
    }

    @Test fun updateTvOnRemovedTvDoesNothing() = runBlocking<Unit> {
        val s = JsonFileCompanionStore(dir)
        s.saveTv(tv)
        s.removeTv("tv1")
        assertEquals(false, s.updateTv("tv1") { it.copy(status = TvStatus.RUNNING) })
        assertEquals(emptyList(), JsonFileCompanionStore(dir).tvs())
        assertFailsWith<IllegalArgumentException> {
            s.saveTv(tv)
            s.updateTv("tv1") { it.copy(serial = "") }
        }
        assertEquals("G070VM1234", JsonFileCompanionStore(dir).tvs().single().serial)
    }

    @Test fun promptedHostsRoundTripAndSaveTvClearsThem() = runBlocking {
        val s = JsonFileCompanionStore(dir)
        val p = listOf(PromptedHost("net1", "192.168.5.23", 1_700_000_000_000L))
        s.savePromptedHosts(p)
        assertEquals(p, JsonFileCompanionStore(dir).promptedHosts())
        s.saveTv(tv)
        assertEquals(emptyList(), JsonFileCompanionStore(dir).promptedHosts())
    }

    @Test fun fileAndDirectoryAreOwnerOnly() = runBlocking {
        val sub = File(dir, "data")
        JsonFileCompanionStore(sub).saveSettings(CompanionSettings())
        val f = File(sub, JsonFileCompanionStore.FILE_NAME).toPath()
        if (Files.getFileAttributeView(f, PosixFileAttributeView::class.java) != null) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(sub.toPath())))
        }
    }
}

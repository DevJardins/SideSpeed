package io.github.devjardins.sidespeed.companion.store

import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.TrustedOverlay
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
import kotlin.test.assertFalse
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

    @Test fun branchWifiFieldsAreIgnoredWhenLoading() = runBlocking {
        // A file saved by this branch while it recorded the Wi-Fi name (schema v3): the trimmed
        // build reads the record and settings without the Wi-Fi keys.
        file.writeText(
            """{"version":3,"trustedNetworks":[{"id":"n","name":"N","fingerprint":{"gatewayIp":"192.168.5.1","subnet":"192.168.5.0/24","dnsServers":["192.168.5.1"],"gatewayMac":"aa:bb:cc:dd:ee:ff","wifiSsid":"Home","wifiBssid":"aa:bb:cc:dd:ee:ff"}}],"settings":{"notifications":false,"intervalMinutes":15,"startAtLogin":false,"useWifiName":true}}""",
        )
        val s = JsonFileCompanionStore(dir)
        assertEquals(
            TrustedNetwork(
                "n", "N",
                NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1"), "aa:bb:cc:dd:ee:ff"),
            ),
            s.trustedNetworks().single(),
        )
        assertEquals(CompanionSettings(), s.settings())
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

    @Test fun reachRoundTripsAndOldFilesLoadWithNull() = runBlocking {
        val s = JsonFileCompanionStore(dir)
        s.saveTv(tv.copy(reach = Reach.REACHED_TAILNET))
        s.saveTv(tv.copy(id = "tv2", serial = "S2", reach = null))
        val r = JsonFileCompanionStore(dir).tvs()
        assertEquals(Reach.REACHED_TAILNET, r.first { it.id == "tv1" }.reach)
        assertEquals(null, r.first { it.id == "tv2" }.reach)

        // A store written before reach existed has no key.
        file.writeText("""{"version":1,"tvs":[{"id":"a","name":"A","address":"1.2.3.4","serial":"S","trustedNetworkId":"n"}],"settings":{}}""")
        assertEquals(null, JsonFileCompanionStore(dir).tvs().single().reach)
        // An unknown value reads as null rather than dropping the TV.
        file.writeText("""{"version":1,"tvs":[{"id":"a","name":"A","address":"1.2.3.4","serial":"S","trustedNetworkId":"n","reach":"REACHED_ORBIT"}],"settings":{}}""")
        assertEquals(null, JsonFileCompanionStore(dir).tvs().single().reach)
    }

    @Test fun linkRoundTripsClassicAndEncrypted() = runBlocking {
        val s = JsonFileCompanionStore(dir)
        val classic = tv.copy(link = TvLink.Classic(ackAtEpochMs = 1_700_000_000_000L))
        val encrypted = tv.copy(id = "tv2", serial = "S2", link = TvLink.Encrypted(lastPort = 37_123))
        val encryptedUnknown = tv.copy(id = "tv3", serial = "S3", link = TvLink.Encrypted(lastPort = null))
        s.saveTv(classic)
        s.saveTv(encrypted)
        s.saveTv(encryptedUnknown)
        val r = JsonFileCompanionStore(dir).tvs()
        assertEquals(classic, r.first { it.id == "tv1" })
        assertEquals(encrypted, r.first { it.id == "tv2" })
        assertEquals(encryptedUnknown, r.first { it.id == "tv3" })
    }

    @Test fun aFileWrittenBeforeLinksLoadsEveryTvAsUnacknowledgedClassic() = runBlocking {
        file.writeText(
            """{"version":2,"tvs":[{"id":"a","name":"A","address":"192.168.5.20","serial":"S","trustedNetworkId":"n","status":"RUNNING"}],"settings":{}}""",
        )
        val loaded = JsonFileCompanionStore(dir).tvs().single()
        assertEquals(TvLink.Classic(ackAtEpochMs = null), loaded.link)
        assertEquals("a", loaded.id)
    }

    @Test fun isFireTvRoundTripsAndDefaultsToTrue() = runBlocking {
        val s = JsonFileCompanionStore(dir)
        s.saveTv(tv.copy(isFireTv = false))
        assertEquals(false, JsonFileCompanionStore(dir).tvs().single().isFireTv)
        assertEquals(true, SavedTv("a", "n", "1.2.3.4", "S", "net").isFireTv)
    }

    @Test fun overlaysAndPeerBindingsRoundTripAndOldFilesLoadWithNone() = runBlocking {
        val overlay = TrustedOverlay("overlay-1", "Home tailnet", "me@example.com", "nSELF")
        val bound = tv.copy(id = "tv3", serial = "S3", trustedNetworkId = overlay.id, overlayPeerId = "nCUBE")
        val s = JsonFileCompanionStore(dir)
        s.saveTrustedOverlay(overlay)
        s.saveTv(bound)
        s.saveTv(tv)
        val r = JsonFileCompanionStore(dir)
        assertEquals(listOf(overlay), r.trustedOverlays())
        assertEquals(bound, r.tvs().first { it.id == "tv3" })
        assertEquals(null, r.tvs().first { it.id == "tv1" }.overlayPeerId)
        r.saveTrustedOverlay(overlay.copy(name = "Renamed"))
        assertEquals("Renamed", r.trustedOverlays().single().name)
        r.removeTrustedOverlay(overlay.id)
        assertEquals(emptyList(), JsonFileCompanionStore(dir).trustedOverlays())

        // A store written before overlays existed has neither key.
        file.writeText("""{"version":1,"tvs":[],"trustedNetworks":[],"settings":{}}""")
        assertEquals(emptyList(), JsonFileCompanionStore(dir).trustedOverlays())
    }

    @Test fun malformedOverlayEntriesAreSkipped() = runBlocking {
        file.writeText("""{"version":1,"trustedOverlays":[{"id":"o1","name":"ok","tailnet":"t","selfId":"s"},{"id":"o2"},5,{"id":"o3","name":"n","tailnet":"","selfId":"s"}]}""")
        assertEquals(listOf("o1"), JsonFileCompanionStore(dir).trustedOverlays().map { it.id })
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

    @Test fun newerFileIsNeverRewrittenAndFlagIsSet() = runBlocking {
        val original = """{"version":5,"tvs":[{"id":"tv1","name":"TV","address":"192.168.5.50","serial":"S","trustedNetworkId":"net1","status":"RUNNING"}],"trustedNetworks":[],"trustedOverlays":[],"promptedHosts":[],"settings":{"notifications":false,"intervalMinutes":15,"startAtLogin":false}}"""
        file.writeText(original)
        val s = JsonFileCompanionStore(dir)
        assertTrue(s.savedByNewerVersion, "a v5 file sets the newer-data flag")
        assertEquals(listOf("tv1"), s.tvs().map { it.id }, "a newer file still loads what can be read")
        s.saveTv(tv.copy(id = "tv2", serial = "S2"))
        assertFalse(s.updateTv("tv1") { it.copy(name = "Renamed") })
        s.saveTrustedNetwork(network)
        s.saveSettings(CompanionSettings(notifications = true))
        s.removeTv("tv1")
        assertEquals(listOf("tv1"), s.tvs().map { it.id }, "saves are dropped in memory too")
        assertEquals(original, file.readText(), "no save may rewrite a newer file")
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".bak") }, "no backup is taken for a newer file")
    }

    @Test fun olderFileIsBackedUpOnFirstSaveThenWrittenAtTheCurrentVersion() = runBlocking {
        val original = """{"version":1,"tvs":[],"trustedNetworks":[],"settings":{}}"""
        file.writeText(original)
        File(dir, "companion.json.v9.bak").writeText("stale")
        val s = JsonFileCompanionStore(dir)
        assertFalse(s.savedByNewerVersion)
        s.saveSettings(CompanionSettings(intervalMinutes = 60))
        val bak = File(dir, "${JsonFileCompanionStore.FILE_NAME}.v1.bak")
        assertEquals(original, bak.readText(), "the original v1 file is kept next to the new one")
        assertEquals(null, dir.listFiles()!!.firstOrNull { it.name.endsWith(".v9.bak") }, "only the most recent backup is kept")
        val text = file.readText()
        assertTrue(text.contains("\"version\":4"), "the upgraded file says v4: $text")
        assertEquals(60, JsonFileCompanionStore(dir).settings().intervalMinutes)
        s.saveSettings(CompanionSettings(intervalMinutes = 30))
        assertEquals(original, bak.readText(), "the backup is taken only on the first save")
        assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".bak") })
    }

    @Test fun missingVersionIsTreatedAsV1() = runBlocking {
        val original = """{"tvs":[],"settings":{}}"""
        file.writeText(original)
        val s = JsonFileCompanionStore(dir)
        assertFalse(s.savedByNewerVersion)
        s.saveSettings(CompanionSettings())
        assertEquals(original, File(dir, "${JsonFileCompanionStore.FILE_NAME}.v1.bak").readText())
        assertTrue(file.readText().contains("\"version\":4"))
    }

    @Test fun invalidVersionIsTreatedAsV1() = runBlocking {
        val original = """{"version":"three","tvs":[],"settings":{}}"""
        file.writeText(original)
        val s = JsonFileCompanionStore(dir)
        assertFalse(s.savedByNewerVersion)
        s.saveSettings(CompanionSettings())
        assertEquals(original, File(dir, "${JsonFileCompanionStore.FILE_NAME}.v1.bak").readText())
    }

    @Test fun currentFileRoundTripsWithNoBackup() = runBlocking {
        val s = JsonFileCompanionStore(dir)
        s.saveTv(tv)
        s.saveSettings(CompanionSettings(intervalMinutes = 60))
        assertTrue(file.readText().contains("\"version\":4"), "fresh saves say v4")
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".bak") }, "no backup for a current file")
        val r = JsonFileCompanionStore(dir)
        assertFalse(r.savedByNewerVersion)
        assertEquals(tv, r.tvs().single())
        r.saveSettings(CompanionSettings(intervalMinutes = 30))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".bak") })
        assertEquals(30, JsonFileCompanionStore(dir).settings().intervalMinutes)
    }

    @Test fun secretRoundTripsAndAnOldFileLoadsWithoutOne() = runBlocking {
        val secret = "ab".repeat(32)
        val s = JsonFileCompanionStore(dir)
        s.saveTv(tv.copy(identitySecret = secret, unproven = true))
        val loaded = JsonFileCompanionStore(dir).tvs().single()
        assertEquals(secret, loaded.identitySecret)
        assertEquals(true, loaded.unproven)

        file.writeText("""{"version":3,"tvs":[{"id":"a","name":"A","address":"1.2.3.4","serial":"S","trustedNetworkId":"n"}],"settings":{}}""")
        val old = JsonFileCompanionStore(dir).tvs().single()
        assertEquals(null, old.identitySecret)
        assertEquals(false, old.unproven)

        file.writeText("""{"version":4,"tvs":[{"id":"a","name":"A","address":"1.2.3.4","serial":"S","trustedNetworkId":"n","identitySecret":"NOPE"}],"settings":{}}""")
        assertEquals(null, JsonFileCompanionStore(dir).tvs().single().identitySecret)
        assertFailsWith<IllegalArgumentException> { s.saveTv(tv.copy(identitySecret = "AB".repeat(32))) }
        Unit
    }

    @Test fun language_round_trips() = runBlocking {
        val s = JsonFileCompanionStore(dir)
        s.saveSettings(CompanionSettings(language = "ar"))
        assertEquals("ar", JsonFileCompanionStore(dir).settings().language)
    }

    @Test fun missing_language_reads_null() = runBlocking {
        file.writeText("""{"version":4,"settings":{"notifications":true,"intervalMinutes":60,"startAtLogin":true}}""")
        val loaded = JsonFileCompanionStore(dir).settings()
        assertEquals(null, loaded.language)
        assertEquals(CompanionSettings(notifications = true, intervalMinutes = 60, startAtLogin = true), loaded)
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

package io.github.devjardins.sidespeed.updates

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.ui.apps.myAppsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    private fun app(
        pkg: String = "org.smarttube.tv",
        label: String = "SmartTube",
        installer: String? = null,
        isSystem: Boolean = false,
        versionName: String? = "29.46",
        versionCode: Long = 2946L,
    ) = AppEntry(pkg, label, installer, 0L, isSystem, versionName, versionCode)

    private fun entry(
        versionName: String = "29.47",
        source: String? = "github",
        name: String = "SmartTube",
    ) = UpdateEntry(name, versionName, source, "https://example.com/page", "https://sidespeed.app/u/x")

    private val fixture = """
        {
          "schema": 1,
          "generated": "2026-11-20T00:00:00Z",
          "unknownTop": [1, 2],
          "apps": {
            "org.smarttube.tv": {
              "name": "SmartTube",
              "versionName": "29.47",
              "versionCode": 2947,
              "source": "github",
              "page": "https://github.com/yuliskov/SmartTube/releases",
              "short": "https://sidespeed.app/u/smarttube",
              "future": true
            },
            "com.example.app": {
              "name": "Example",
              "versionName": "1.2.10",
              "source": "fdroid",
              "page": "https://f-droid.org/packages/com.example.app"
            }
          }
        }
    """.trimIndent()

    @Test fun parseValidCatalog() {
        val catalog = parseAppUpdates(fixture)!!
        assertEquals(2, catalog.size)
        val tube = catalog["org.smarttube.tv"]!!
        assertEquals("SmartTube", tube.name)
        assertEquals("29.47", tube.versionName)
        assertEquals("github", tube.source)
        assertEquals("https://sidespeed.app/u/smarttube", tube.short)
        val example = catalog["com.example.app"]!!
        assertEquals("1.2.10", example.versionName)
        assertNull(example.short)
    }

    @Test fun parseIgnoresVersionCodeField() {
        // The catalog's version code is never read, even when present.
        val json = """{"schema":1,"apps":{"a.b":{"name":"A","versionName":"2","versionCode":99}}}"""
        assertEquals("2", parseAppUpdates(json)!!["a.b"]!!.versionName)
    }

    @Test fun parseRejectsSchemaMismatch() {
        assertNull(parseAppUpdates(fixture.replace("\"schema\": 1", "\"schema\": 2")))
        assertNull(parseAppUpdates("""{"apps": {}}"""))
        assertNull(parseAppUpdates("not json at all"))
        assertNull(parseAppUpdates(""))
    }

    @Test fun parseRejectsTruncatedBody() {
        assertNull(parseAppUpdates(fixture.substring(0, fixture.length - 10)))
    }

    @Test fun parseSkipsEntriesWithoutVersionName() {
        val json = """{"schema":1,"apps":{"a.b":{"name":"No version"},"c.d":{"name":"Ok","versionName":"2"}}}"""
        assertEquals(setOf("c.d"), parseAppUpdates(json)!!.keys)
    }

    @Test fun parseSkipsNonStringVersionName() {
        val json = """{"schema":1,"apps":{"a.b":{"name":"Number","versionName":29},"c.d":{"name":"Ok","versionName":"2"}}}"""
        assertEquals(setOf("c.d"), parseAppUpdates(json)!!.keys)
    }

    @Test fun parseSkipsNonObjectEntries() {
        val json = """{"schema":1,"apps":{"a.b":"nope","c.d":{"name":"Ok","versionName":"2"}}}"""
        assertEquals(setOf("c.d"), parseAppUpdates(json)!!.keys)
    }

    @Test fun parseFiltersToInstalledPackages() {
        val catalog = parseAppUpdates(fixture, setOf("com.example.app"))!!
        assertEquals(setOf("com.example.app"), catalog.keys)
    }

    @Test fun numericRunsCompare() {
        assertTrue(compareVersionNames("1.2.10", "1.2.9")!! > 0)
        assertTrue(compareVersionNames("1.2.9", "1.2.10")!! < 0)
        assertTrue(compareVersionNames("32.80", "32.79")!! > 0)
        assertTrue(compareVersionNames("32.80s", "32.79")!! > 0)
    }

    @Test fun leadingVIsIgnored() {
        assertEquals(0, compareVersionNames("v29.47", "29.47"))
        assertTrue(compareVersionNames("v29.47", "29.46")!! > 0)
    }

    @Test fun missingPartsCountAsZero() {
        assertEquals(0, compareVersionNames("1.0", "1.0.0"))
        assertTrue(compareVersionNames("1.0.1", "1.0")!! > 0)
    }

    @Test fun preReleaseIsUncertain() {
        assertNull(compareVersionNames("29.47-beta", "29.46"))
        assertNull(compareVersionNames("29.47", "29.47-beta"))
        assertNull(compareVersionNames("1.1.0", "1.2.0-beta.2"))
        assertNull(compareVersionNames("beta", "1.0"))
        assertNull(compareVersionNames("", "1.0"))
        assertNull(compareVersionNames("1.0", ""))
    }

    @Test fun arcIsNotARcMarker() {
        // "arc" contains "rc" but not as a word: -arc.4 still badges over -arc.3.
        assertTrue(compareVersionNames("1.2.0-arc.4", "1.2.0-arc.3")!! > 0)
        assertEquals(0, compareVersionNames("1.2.0-arc.3", "1.2.0-arc.3"))
    }

    @Test fun arcUpdateBadges() {
        val u = updateFor(
            app(versionName = "1.2.0-arc.3"),
            "1.2.0-arc.3",
            entry(versionName = "1.2.0-arc.4", source = null, name = "Arc"),
        )
        assertEquals("1.2.0-arc.4", u!!.versionName)
    }

    @Test fun arcSameIsNoBadge() {
        assertNull(updateFor(app(versionName = "1.2.0-arc.3"), "1.2.0-arc.3", entry(versionName = "1.2.0-arc.3")))
    }

    @Test fun betaInstalledIsNoBadge() {
        assertNull(updateFor(app(versionName = "1.2.0-beta.2"), "1.2.0-beta.2", entry(versionName = "1.1.0")))
    }

    @Test fun newerByNameBadges() {
        val u = updateFor(app(versionName = "1.2.9", versionCode = 0L), "1.2.9", entry(versionName = "1.2.10"))
        assertEquals("1.2.10", u!!.versionName)
        assertEquals("Update: 1.2.10", updatePillLabel(u.versionName))
    }

    @Test fun vPrefixNewerBadges() {
        val u = updateFor(app(), "29.46", entry(versionName = "v29.47"))
        assertEquals("v29.47", u!!.versionName)
    }

    @Test fun equalNameIsNoBadge() {
        assertNull(updateFor(app(versionName = "1.0", versionCode = 0L), "1.0", entry(versionName = "1.0.0")))
    }

    @Test fun olderNameIsNoBadge() {
        assertNull(updateFor(app(versionName = "1.2.10", versionCode = 0L), "1.2.10", entry(versionName = "1.2.9")))
    }

    @Test fun betaNameIsNoBadge() {
        assertNull(updateFor(app(versionName = "29.46", versionCode = 0L), "29.46", entry(versionName = "29.47-beta")))
        assertNull(updateFor(app(versionName = "29.47-beta", versionCode = 0L), "29.47-beta", entry(versionName = "29.47")))
    }

    @Test fun versionCodeIsNeverUsed() {
        // Catalog code larger but same version name: no badge.
        assertNull(updateFor(app(versionName = "29.47", versionCode = 2946L), "29.47", entry()))
        // Catalog name newer: badges regardless of the installed code.
        assertNotNull(updateFor(app(versionName = "29.40", versionCode = 9999L), "29.40", entry()))
    }

    @Test fun systemAppIsNoBadge() {
        assertNull(updateFor(app(isSystem = true), "29.46", entry()))
    }

    @Test fun storeAppIsNoBadge() {
        assertNull(updateFor(app(installer = "com.android.vending"), "29.46", entry()))
        assertNull(updateFor(app(installer = "com.amazon.venezia"), "29.46", entry()))
    }

    @Test fun missingEntryIsNoBadge() {
        assertNull(updateFor(app(), "29.46", null))
    }

    @Test fun blankCatalogVersionIsNoBadge() {
        assertNull(updateFor(app(), "29.46", entry(versionName = "")))
    }

    @Test fun blankInstalledVersionIsNoBadge() {
        assertNull(updateFor(app(versionName = null), null, entry()))
        assertNull(updateFor(app(versionName = ""), "", entry()))
    }

    @Test fun nameFallsBackToInstalledLabel() {
        val u = updateFor(app(), "29.46", entry(name = "", versionName = "29.47"))
        assertEquals("SmartTube", u!!.name)
    }

    @Test fun linkPrefersShort() {
        val u = updateFor(app(), "29.46", entry())
        assertEquals("https://sidespeed.app/u/x", u!!.link)
    }

    @Test fun linkFallsBackToPage() {
        val e = UpdateEntry("N", "2", "fdroid", "https://f-droid.org/p/a.b", null)
        assertEquals("https://f-droid.org/p/a.b", updateFor(app(), "1", e)!!.link)
    }

    @Test fun linkNullWhenNeither() {
        val e = UpdateEntry("N", "2", null, null, null)
        assertNull(updateFor(app(), "1", e)!!.link)
    }

    @Test fun cardTitle() {
        assertEquals("SmartTube 29.47 is out. You have 29.46.", updateCardTitle("SmartTube", "29.47", "29.46"))
    }

    @Test fun sourceLines() {
        assertEquals("From GitHub.", updateSourceLine("github"))
        assertEquals("From F-Droid.", updateSourceLine("fdroid"))
        assertEquals("From IzzyOnDroid.", updateSourceLine("izzy"))
        assertNull(updateSourceLine("unknown"))
        assertNull(updateSourceLine(null))
    }

    @Test fun statusShowsUpdateCount() {
        val apps = listOf(app("a.a"), app("b.b", installer = "com.android.vending"))
        assertEquals("2 apps · 2 updates", myAppsStatus(apps, loading = false, updates = 2))
    }

    @Test fun statusSingularUpdate() {
        assertEquals("1 apps · 1 update", myAppsStatus(listOf(app("a.a")), loading = false, updates = 1))
    }

    @Test fun statusZeroUpdatesUnchanged() {
        val apps = listOf(app("a.a"), app("b.b", installer = "com.android.vending"))
        assertEquals("2 apps · 1 sideloaded", myAppsStatus(apps, loading = false, updates = 0))
        assertEquals("2 apps · 1 sideloaded", myAppsStatus(apps, loading = false))
    }

    @Test fun statusLoadingWinsOverUpdates() {
        assertEquals("Loading apps…", myAppsStatus(emptyList(), loading = true, updates = 3))
    }

    @Test fun countsOnlyBadgedApps() {
        val apps = listOf(
            app("org.smarttube.tv", versionName = "29.46", versionCode = 2946L),
            app("com.example.app", versionName = "1.2.10", versionCode = 0L),
            app("com.store.app", installer = "com.android.vending", versionName = "1", versionCode = 1L),
        )
        val catalog = parseAppUpdates(fixture)!!
        assertEquals(1, myAppsUpdatesCount(apps, catalog))
    }
}

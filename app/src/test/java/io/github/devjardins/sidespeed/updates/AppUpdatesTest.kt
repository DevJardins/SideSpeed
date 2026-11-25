package io.github.devjardins.sidespeed.updates

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.helper.CommandAllowlist
import io.github.devjardins.sidespeed.testing.english
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

    @Test fun parseAcceptsSchema1And2() {
        val one = parseAppUpdates(fixture)!!
        val two = parseAppUpdates(fixture.replace("\"schema\": 1", "\"schema\": 2"))!!
        assertEquals(one, two)
    }

    @Test fun parseAcceptsSchema2WithApk() {
        val json = """{"schema":2,"apps":{"a.b":{"name":"A","versionName":"2","apk":[${variantJson()}]}}}"""
        assertEquals(3L, parseAppUpdates(json)!!["a.b"]!!.apk.single().versionCode)
    }

    @Test fun parseRejectsSchemaMismatch() {
        assertNull(parseAppUpdates(fixture.replace("\"schema\": 1", "\"schema\": 3")))
        assertNull(parseAppUpdates("""{"apps": {}}"""))
        assertNull(parseAppUpdates("not json at all"))
        assertNull(parseAppUpdates(""))
    }

    @Test fun catalogCapIs8MB() {
        assertEquals(8 * 1024 * 1024L, APP_UPDATES_MAX_BYTES)
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
        assertEquals("Update: 1.2.10", updatePillLabel(u.versionName).english())
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
        assertEquals("SmartTube 29.47 is out. You have 29.46.", updateCardTitle("SmartTube", "29.47", "29.46").english())
    }

    @Test fun sourceLines() {
        assertEquals("From GitHub.", updateSourceLine("github")!!.english())
        assertEquals("From F-Droid.", updateSourceLine("fdroid")!!.english())
        assertEquals("From IzzyOnDroid.", updateSourceLine("izzy")!!.english())
        assertNull(updateSourceLine("unknown"))
        assertNull(updateSourceLine(null))
    }

    @Test fun statusShowsUpdateCount() {
        val apps = listOf(app("a.a"), app("b.b", installer = "com.android.vending"))
        assertEquals("2 apps · 2 updates", myAppsStatus(apps, loading = false, updates = 2).english())
    }

    @Test fun statusSingularUpdate() {
        assertEquals("1 apps · 1 update", myAppsStatus(listOf(app("a.a")), loading = false, updates = 1).english())
    }

    @Test fun statusZeroUpdatesUnchanged() {
        val apps = listOf(app("a.a"), app("b.b", installer = "com.android.vending"))
        assertEquals("2 apps · 1 sideloaded", myAppsStatus(apps, loading = false, updates = 0).english())
        assertEquals("2 apps · 1 sideloaded", myAppsStatus(apps, loading = false).english())
    }

    @Test fun statusLoadingWinsOverUpdates() {
        assertEquals("Loading apps…", myAppsStatus(emptyList(), loading = true, updates = 3).english())
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

    private fun variantJson(
        url: String = "https://example.com/a.apk",
        sha256: String = "a".repeat(64),
        size: Any = 12_345,
        versionCode: Any? = 3,
        abis: String? = """["arm64-v8a"]""",
        minSdk: String = ",\"minSdk\":28",
        signer: String = ""","signer":["${"b".repeat(64)}"]""",
    ): String {
        val vc = if (versionCode == null) "" else ""","versionCode":$versionCode"""
        val ab = if (abis == null) "" else ""","abis":$abis"""
        return """{"url":"$url","sha256":"$sha256","size":$size$vc$ab$minSdk$signer}"""
    }

    private fun entryWithApk(vararg variants: String): UpdateEntry {
        val json = """{"schema":1,"apps":{"a.b":{"name":"A","versionName":"2","apk":[${variants.joinToString(",")}]}}}"""
        return parseAppUpdates(json)!!["a.b"]!!
    }

    @Test fun parseApkVariant() {
        val v = entryWithApk(variantJson()).apk.single()
        assertEquals("https://example.com/a.apk", v.url)
        assertEquals("a".repeat(64), v.sha256)
        assertEquals(12_345L, v.size)
        assertEquals(3L, v.versionCode)
        assertEquals(listOf("arm64-v8a"), v.abis)
        assertEquals(28, v.minSdk)
        assertEquals(setOf("b".repeat(64)), v.signer)
    }

    @Test fun parseVariantDefaultsAreUniversal() {
        val v = entryWithApk(variantJson(abis = null, minSdk = "", signer = "")).apk.single()
        assertTrue(v.abis.isEmpty())
        assertNull(v.minSdk)
        assertTrue(v.signer.isEmpty())
    }

    @Test fun parseDropsMalformedVariantsButKeepsEntry() {
        val over = CommandAllowlist.MAX_UPDATE_BYTES + 1
        val entry = entryWithApk(
            variantJson(url = "http://example.com/a.apk"),
            variantJson(sha256 = "zzz"),
            variantJson(sha256 = "A".repeat(64)),
            variantJson(size = 0),
            variantJson(size = over),
            variantJson(versionCode = null),
        )
        assertTrue("malformed variants are dropped: ${entry.apk}", entry.apk.isEmpty())
    }

    @Test fun parseUppercaseSha256IsDropped() {
        // The feed writes lowercase hex; anything else is not well formed.
        val entry = entryWithApk(variantJson(sha256 = "A".repeat(64)))
        assertTrue(entry.apk.isEmpty())
    }

    @Test fun parseDropsBadSignerAndBadAbis() {
        val entry = entryWithApk(
            variantJson(signer = ""","signer":["zzz"]"""),
            variantJson(abis = """["arm64-v8a", 7]"""),
        )
        assertTrue(entry.apk.isEmpty())
    }

    @Test fun parseNoApkIsEmptyList() {
        val json = """{"schema":1,"apps":{"a.b":{"name":"A","versionName":"2"}}}"""
        assertTrue(parseAppUpdates(json)!!["a.b"]!!.apk.isEmpty())
    }

    @Test fun updateForThreadsVariants() {
        val u = updateFor(app(versionName = "1"), "1", entryWithApk(variantJson()))
        assertEquals(1, u!!.apk.size)
        assertEquals(3L, u.apk.single().versionCode)
    }

    private fun pickable(
        abis: List<String> = listOf("arm64-v8a"),
        minSdk: Int? = 28,
        versionCode: Long = 3,
        signer: Set<String> = setOf("b".repeat(64)),
    ) = ApkVariant("https://example.com/a.apk", "a".repeat(64), 100L, versionCode, abis, minSdk, signer)

    @Test fun pickPrefersFirstSupportedAbi() {
        val arm = pickable(abis = listOf("armeabi-v7a"))
        val x64 = pickable(abis = listOf("arm64-v8a"))
        assertEquals(x64, pickVariant(listOf(arm, x64), listOf("arm64-v8a", "armeabi-v7a"), 28, 2L, setOf("b".repeat(64))))
        assertEquals(arm, pickVariant(listOf(arm, x64), listOf("armeabi-v7a", "arm64-v8a"), 28, 2L, setOf("b".repeat(64))))
    }

    @Test fun pickUniversalFallback() {
        val specific = pickable(abis = listOf("x86_64"))
        val universal = pickable(abis = emptyList())
        assertEquals(
            universal,
            pickVariant(listOf(specific, universal), listOf("arm64-v8a"), 28, 2L, setOf("b".repeat(64))),
        )
    }

    @Test fun pickNoMatchIsNull() {
        val specific = pickable(abis = listOf("x86_64"))
        assertNull(pickVariant(listOf(specific), listOf("arm64-v8a"), 28, 2L, setOf("b".repeat(64))))
        assertNull(pickVariant(emptyList(), listOf("arm64-v8a"), 28, 2L, setOf("b".repeat(64))))
    }

    @Test fun pickFiltersMinSdk() {
        val v = pickable(minSdk = 29)
        assertNull(pickVariant(listOf(v), listOf("arm64-v8a"), 28, 2L, setOf("b".repeat(64))))
        assertEquals(v, pickVariant(listOf(v), listOf("arm64-v8a"), 29, 2L, setOf("b".repeat(64))))
        val noMin = v.copy(minSdk = null)
        assertEquals(noMin, pickVariant(listOf(noMin), listOf("arm64-v8a"), 21, 2L, setOf("b".repeat(64))))
    }

    @Test fun pickRequiresNewerVersionCode() {
        val v = pickable(versionCode = 3)
        assertNull(pickVariant(listOf(v), listOf("arm64-v8a"), 28, 3L, setOf("b".repeat(64))))
        assertNull(pickVariant(listOf(v), listOf("arm64-v8a"), 28, 4L, setOf("b".repeat(64))))
        assertEquals(v, pickVariant(listOf(v), listOf("arm64-v8a"), 28, 2L, setOf("b".repeat(64))))
    }

    @Test fun pickSignerMismatchIsNull() {
        val v = pickable()
        assertNull(pickVariant(listOf(v), listOf("arm64-v8a"), 28, 2L, setOf("c".repeat(64))))
    }

    @Test fun pickSignerAbsentIsAllowed() {
        val v = pickable(signer = emptySet())
        assertEquals(
            v,
            pickVariant(listOf(v), listOf("arm64-v8a"), 28, 2L, setOf("c".repeat(64))),
        )
    }
}

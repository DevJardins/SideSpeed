package io.github.devjardins.sidespeed.companion.install

import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.AxmlSpec
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.ByteArrayInstallSource
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.ArscEntrySpec
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.ArscValue
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.TempZipInstallSource
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.buildApkBytes
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.buildArsc
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.buildAxml
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.buildMultiVariantApks
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.buildTocBytes
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.buildZipBytes
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.TocVariantSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApkInspectorTest {
    @Test
    fun inspects_single_apk_with_over_1000_resource_entries() {
        val apkBytes = buildApkBytes(
            spec = AxmlSpec(
                packageName = "com.example.large",
                versionCode = 120,
                versionName = "3.4.5",
                appLabelLiteral = "Large App",
            ),
            dummyEntriesBeforeManifest = 1_200,
        )

        ByteArrayInstallSource("LargeApp.apk", apkBytes).use { streamed ->
            val inspected = ApkInspector.inspect(streamed).getOrThrow()
            assertEquals("com.example.large", inspected.packageName)
            assertEquals("Large App", inspected.displayLabel)
            assertEquals(120L, inspected.versionCode)
            assertEquals("3.4.5", inspected.versionName)
            assertFalse(inspected.isBundle)
            assertNull(inspected.base.zipEntryName)
            assertEquals(apkBytes.size.toLong(), inspected.base.sizeBytes)
            assertTrue(inspected.splits.isEmpty())
        }

        ByteArrayInstallSource("UnknownSize.apk", apkBytes, sizeBytes = null).use { unknownSize ->
            val inspected = ApkInspector.inspect(unknownSize).getOrThrow()
            assertEquals(apkBytes.size.toLong(), inspected.base.sizeBytes)
        }

        TempZipInstallSource.fromBytes("LargeApp.apk", apkBytes).use { zipBacked ->
            val inspected = ApkInspector.inspect(zipBacked).getOrThrow()
            assertEquals("com.example.large", inspected.packageName)
            assertFalse(inspected.isBundle)
        }
    }

    @Test
    fun inspects_apks_xapk_apkm_with_80_locale_splits_and_ignores_bundletool_standalones() {
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries += "toc.pb" to byteArrayOf(1, 2, 3)
        entries += "splits/base-master.apk" to buildApkBytes(
            AxmlSpec(
                packageName = "com.example.bundle",
                versionCode = 500,
                versionName = "5.0.0",
                appLabelResId = 0x7f0e0001,
            ),
        )
        entries += "splits/base-arm64_v8a.apk" to buildApkBytes(
            AxmlSpec(
                packageName = "com.example.bundle",
                versionCode = 500,
                versionName = "5.0.0",
                splitName = "config.arm64_v8a",
            ),
        )
        entries += "splits/base-xhdpi.apk" to buildApkBytes(
            AxmlSpec(
                packageName = "com.example.bundle",
                versionCode = 500,
                versionName = "5.0.0",
                splitName = "config.xhdpi",
            ),
        )
        for (i in 1..80) {
            val lang = "l${(i / 26 + 'a'.code).toChar()}${(i % 26 + 'a'.code).toChar()}"
            entries += "splits/base-$lang.apk" to buildApkBytes(
                AxmlSpec(
                    packageName = "com.example.bundle",
                    versionCode = 500,
                    versionName = "5.0.0",
                    splitName = "config.$lang",
                ),
            )
        }
        // bundletool emits standalones/ for pre-Lollipop; must be ignored when splits/ exists.
        entries += "standalones/standalone-hdpi.apk" to buildApkBytes(
            AxmlSpec(
                packageName = "com.example.bundle",
                versionCode = 500,
                versionName = "5.0.0",
            ),
        )

        val bundleBytes = buildZipBytes(entries)
        TempZipInstallSource.fromBytes("My Bundle.apks", bundleBytes).use { source ->
            val inspected = ApkInspector.inspect(source).getOrThrow()
            assertTrue(inspected.isBundle)
            assertEquals("com.example.bundle", inspected.packageName)
            assertEquals("My Bundle", inspected.displayLabel)
            assertEquals("splits/base-master.apk", inspected.base.zipEntryName)
            assertEquals(82, inspected.splits.size)
            assertTrue(inspected.splits.none { it.zipEntryName?.startsWith("standalones/") == true })
        }
    }

    @Test
    fun reads_display_label_hint_from_apkm_info_json_and_xapk_manifest_json() {
        val baseApk = buildApkBytes(
            AxmlSpec(
                packageName = "com.example.hinted",
                versionCode = 10,
                appLabelResId = 0x7f0e0001,
            ),
        )
        val apkmBytes = buildZipBytes(
            listOf(
                "base.apk" to baseApk,
                "info.json" to """{"app_name":"Hinted From APKM","pname":"com.wrong.pkg"}""".encodeToByteArray(),
            ),
        )
        TempZipInstallSource.fromBytes("ignored-filename.apkm", apkmBytes).use { source ->
            val inspected = ApkInspector.inspect(source).getOrThrow()
            assertEquals("Hinted From APKM", inspected.displayLabel)
            assertEquals("com.example.hinted", inspected.packageName)
        }

        val xapkBytes = buildZipBytes(
            listOf(
                "com.example.hinted.apk" to baseApk,
                "manifest.json" to """{"name":"Hinted From XAPK","package_name":"com.wrong.pkg"}""".encodeToByteArray(),
            ),
        )
        TempZipInstallSource.fromBytes("ignored-filename.xapk", xapkBytes).use { source ->
            val inspected = ApkInspector.inspect(source).getOrThrow()
            assertEquals("Hinted From XAPK", inspected.displayLabel)
            assertEquals("com.example.hinted", inspected.packageName)
        }
    }

    @Test
    fun resolves_resource_label_for_single_apk_in_zip_and_stream() {
        val arsc = buildArsc(listOf(ArscEntrySpec("app_name", default = ArscValue.Str("Resolved App"))))
        val spec = AxmlSpec(
            packageName = "com.example.resolved",
            versionCode = 7,
            appLabelResId = 0x7f010000,
        )

        TempZipInstallSource.fromBytes("companion-single.apk", buildApkBytes(spec, arscBytes = arsc)).use {
            assertEquals("Resolved App", ApkInspector.inspect(it).getOrThrow().displayLabel)
        }

        // Streamed inspection reads entries in order; arsc may come before or after the manifest.
        for (arscFirst in listOf(false, true)) {
            ByteArrayInstallSource(
                "companion-single.apk",
                buildApkBytes(spec, arscBytes = arsc, arscBeforeManifest = arscFirst),
            ).use {
                assertEquals("Resolved App", ApkInspector.inspect(it).getOrThrow().displayLabel)
            }
        }
    }

    @Test
    fun prefers_resolved_resource_label_over_json_hint_and_caps_length() {
        val arsc = buildArsc(listOf(ArscEntrySpec("app_name", default = ArscValue.Str("From Arsc"))))
        val baseApk = buildApkBytes(
            AxmlSpec(packageName = "com.example.hinted", versionCode = 10, appLabelResId = 0x7f010000),
            arscBytes = arsc,
        )
        val bundleBytes = buildZipBytes(
            listOf(
                "base.apk" to baseApk,
                "info.json" to """{"app_name":"From Hint","pname":"com.example.hinted"}""".encodeToByteArray(),
            ),
        )
        TempZipInstallSource.fromBytes("ignored-filename.apkm", bundleBytes).use {
            assertEquals("From Arsc", ApkInspector.inspect(it).getOrThrow().displayLabel)
        }

        val longArsc = buildArsc(listOf(ArscEntrySpec("app_name", default = ArscValue.Str("L".repeat(150)))))
        ByteArrayInstallSource(
            "long.apk",
            buildApkBytes(
                AxmlSpec(packageName = "com.example.long", versionCode = 1, appLabelResId = 0x7f010000),
                arscBytes = longArsc,
            ),
        ).use {
            val label = ApkInspector.inspect(it).getOrThrow().displayLabel
            assertEquals(100, label.length)
            assertEquals("L".repeat(100), label)
        }
    }

    @Test
    fun falls_back_to_file_stem_when_arsc_is_malformed() {
        val spec = AxmlSpec(
            packageName = "com.example.broken",
            versionCode = 3,
            appLabelResId = 0x7f010000,
        )
        val valid = buildArsc(listOf(ArscEntrySpec("app_name", default = ArscValue.Str("Resolved App"))))
        for (broken in listOf(valid.copyOf(valid.size / 2), "garbage".encodeToByteArray())) {
            TempZipInstallSource.fromBytes("Fallback Stem.apk", buildApkBytes(spec, arscBytes = broken)).use {
                assertEquals("Fallback Stem", ApkInspector.inspect(it).getOrThrow().displayLabel)
            }
            ByteArrayInstallSource("Fallback Stem.apk", buildApkBytes(spec, arscBytes = broken)).use {
                assertEquals("Fallback Stem", ApkInspector.inspect(it).getOrThrow().displayLabel)
            }
        }
    }

    @Test
    fun resolves_resource_label_for_bundle_base_and_multi_variant() {
        val arsc = buildArsc(listOf(ArscEntrySpec("app_name", default = ArscValue.Str("Bundle App"))))
        val baseApk = buildApkBytes(
            AxmlSpec(packageName = "com.example.bundle", versionCode = 9, appLabelResId = 0x7f010000),
            arscBytes = arsc,
        )
        val splitApk = buildApkBytes(
            AxmlSpec(packageName = "com.example.bundle", versionCode = 9, splitName = "config.en"),
        )
        TempZipInstallSource.fromBytes(
            "bundle.apks",
            buildZipBytes(listOf("base.apk" to baseApk, "split.apk" to splitApk)),
        ).use {
            val inspected = ApkInspector.inspect(it).getOrThrow()
            assertTrue(inspected.isBundle)
            assertEquals("Bundle App", inspected.displayLabel)
        }

        val variantArsc = buildArsc(listOf(ArscEntrySpec("app_name", default = ArscValue.Str("Variant App"))))
        TempZipInstallSource.fromBytes(
            "multi.apks",
            buildMultiVariantApks(baseLabelResId = 0x7f010000, baseArscBytes = variantArsc),
        ).use {
            val inspected = ApkInspector.inspect(it).getOrThrow()
            assertEquals("Variant App", inspected.displayLabel)
            assertEquals("Variant App", ApkInspector.selectVariant(inspected, 28).getOrThrow().displayLabel)
        }
    }

    @Test
    fun rejects_obb_bundle_case_insensitively() {
        val baseApk = buildApkBytes(AxmlSpec(packageName = "com.foo.game", versionCode = 1))
        for (obbPath in listOf("Android/obb/com.foo/main.1.obb", "ANDROID/OBB/com.foo/patch.obb")) {
            val bytes = buildZipBytes(
                listOf(
                    "base.apk" to baseApk,
                    obbPath to byteArrayOf(1, 2, 3),
                ),
            )
            TempZipInstallSource.fromBytes("game.xapk", bytes).use { source ->
                val result = ApkInspector.inspect(source)
                assertTrue(result.isFailure)
                assertEquals(InstallError.ObbNotSupported, result.installErrorOrNull())
            }
        }
    }

    @Test
    fun rejects_mixed_package_or_mismatched_version_or_missing_base_or_double_base() {
        val baseV1 = buildApkBytes(AxmlSpec(packageName = "com.example.app", versionCode = 10))
        val splitWrongPkg = buildApkBytes(AxmlSpec(packageName = "com.other.app", versionCode = 10, splitName = "config.arm64_v8a"))
        val splitWrongVer = buildApkBytes(AxmlSpec(packageName = "com.example.app", versionCode = 11, splitName = "config.arm64_v8a"))
        val splitValid = buildApkBytes(AxmlSpec(packageName = "com.example.app", versionCode = 10, splitName = "config.arm64_v8a"))

        TempZipInstallSource.fromBytes("mixed-pkg.apks", buildZipBytes(listOf("base.apk" to baseV1, "s.apk" to splitWrongPkg))).use {
            assertEquals(InstallError.MixedBundle, ApkInspector.inspect(it).installErrorOrNull())
        }
        TempZipInstallSource.fromBytes("mixed-ver.apks", buildZipBytes(listOf("base.apk" to baseV1, "s.apk" to splitWrongVer))).use {
            assertEquals(InstallError.MixedBundle, ApkInspector.inspect(it).installErrorOrNull())
        }
        TempZipInstallSource.fromBytes("no-base.apks", buildZipBytes(listOf("s.apk" to splitValid))).use {
            assertEquals(InstallError.MissingBaseApk, ApkInspector.inspect(it).installErrorOrNull())
        }
        TempZipInstallSource.fromBytes("double-base.apks", buildZipBytes(listOf("base1.apk" to baseV1, "base2.apk" to baseV1))).use {
            assertEquals(InstallError.MixedBundle, ApkInspector.inspect(it).installErrorOrNull())
        }
    }

    @Test
    fun rejects_oversized_manifest_over_4mib_and_outer_bundle_over_1024_entries_and_over_2gib() {
        // Non-ZIP magic -> NotAnApk
        ByteArrayInstallSource("readme.txt", "not a zip".encodeToByteArray()).use {
            assertEquals(InstallError.NotAnApk, ApkInspector.inspect(it).installErrorOrNull())
        }

        // Valid ZIP without manifest or APK entries -> NotAnApk
        val plainZip = buildZipBytes(listOf("docs/readme.txt" to "hello".encodeToByteArray()))
        TempZipInstallSource.fromBytes("archive.zip", plainZip).use {
            assertEquals(InstallError.NotAnApk, ApkInspector.inspect(it).installErrorOrNull())
        }

        // Manifest > 4 MiB -> TooLarge
        val oversizedManifest = ByteArray(ApkInspector.MAX_MANIFEST_BYTES + 1)
        val oversizedSingleApk = buildApkBytes(
            spec = AxmlSpec(),
            rawManifestOverride = oversizedManifest,
        )
        ByteArrayInstallSource("bigmanifest.apk", oversizedSingleApk).use {
            assertEquals(InstallError.TooLarge, ApkInspector.inspect(it).installErrorOrNull())
        }

        // Reported file size > 2 GiB -> TooLarge
        val tinyValidApk = buildApkBytes(AxmlSpec(packageName = "com.example.tiny", versionCode = 1))
        ByteArrayInstallSource("huge.apk", tinyValidApk, sizeBytes = ApkInspector.MAX_INSTALL_BYTES + 1L).use {
            assertEquals(InstallError.TooLarge, ApkInspector.inspect(it).installErrorOrNull())
        }

        // Outer bundle > 1,024 entries -> TooManyEntries
        val tooManyEntries = ArrayList<Pair<String, ByteArray>>(ApkInspector.MAX_BUNDLE_ENTRIES + 2)
        tooManyEntries += "base.apk" to tinyValidApk
        for (i in 1..ApkInspector.MAX_BUNDLE_ENTRIES + 1) {
            tooManyEntries += "extra/f_$i.txt" to byteArrayOf(1)
        }
        TempZipInstallSource.fromBytes("many.apks", buildZipBytes(tooManyEntries)).use {
            assertEquals(InstallError.TooManyEntries, ApkInspector.inspect(it).installErrorOrNull())
        }

        // Outer bundle > 512 APKs -> TooManyEntries
        val splitBytes = buildApkBytes(AxmlSpec(packageName = "com.example.tiny", versionCode = 1, splitName = "config.en"))
        val tooManyApks = ArrayList<Pair<String, ByteArray>>(ApkInspector.MAX_BUNDLE_APKS + 2)
        tooManyApks += "base.apk" to tinyValidApk
        for (i in 1..ApkInspector.MAX_BUNDLE_APKS) {
            tooManyApks += "s_$i.apk" to splitBytes
        }
        TempZipInstallSource.fromBytes("many-apks.apks", buildZipBytes(tooManyApks)).use {
            assertEquals(InstallError.TooManyEntries, ApkInspector.inspect(it).installErrorOrNull())
        }

        // Inner APK inside bundle with manifest > 4 MiB -> TooLarge
        val bundleBigInnerManifest = buildZipBytes(
            listOf(
                "base.apk" to tinyValidApk,
                "config.en.apk" to buildApkBytes(AxmlSpec(), rawManifestOverride = oversizedManifest),
            ),
        )
        TempZipInstallSource.fromBytes("big-inner.apks", bundleBigInnerManifest).use {
            assertEquals(InstallError.TooLarge, ApkInspector.inspect(it).installErrorOrNull())
        }
    }

    @Test
    fun rejects_corrupt_manifest_and_corrupt_inner_apk_while_ignoring_bad_hint_json() {
        val corruptSingle = buildApkBytes(AxmlSpec(), rawManifestOverride = byteArrayOf(1, 2, 3, 4))
        ByteArrayInstallSource("corrupt.apk", corruptSingle).use {
            assertEquals(InstallError.CorruptArchive, ApkInspector.inspect(it).installErrorOrNull())
        }

        val validBase = buildApkBytes(AxmlSpec(packageName = "com.example.ok", versionCode = 1, appLabelResId = 0x7f0e0001))
        val corruptInner = buildZipBytes(listOf("base.apk" to validBase, "bad.apk" to "not a zip".encodeToByteArray()))
        TempZipInstallSource.fromBytes("corrupt-inner.apks", corruptInner).use {
            assertEquals(InstallError.CorruptArchive, ApkInspector.inspect(it).installErrorOrNull())
        }

        // Malformed info.json does not fail inspection; falls back to filename stem.
        val badJsonBundle = buildZipBytes(
            listOf(
                "base.apk" to validBase,
                "info.json" to "{not valid json".encodeToByteArray(),
            ),
        )
        TempZipInstallSource.fromBytes("Fallback Stem.apkm", badJsonBundle).use {
            val inspected = ApkInspector.inspect(it).getOrThrow()
            assertEquals("Fallback Stem", inspected.displayLabel)
        }
    }

    @Test
    fun inspects_multi_variant_bundletool_apks_and_selects_variant_for_tv_sdk() {
        TempZipInstallSource.fromBytes("multi.apks", buildMultiVariantApks()).use { source ->
            val inspected = ApkInspector.inspect(source).getOrThrow()
            assertTrue(inspected.isBundle)
            assertEquals("com.example.multi", inspected.packageName)
            assertEquals(3, inspected.variants?.size)

            val for21 = ApkInspector.selectVariant(inspected, 21).getOrThrow()
            assertEquals("splits/base-master.apk", for21.base.zipEntryName)
            assertNull(for21.variants)

            val for28 = ApkInspector.selectVariant(inspected, 28).getOrThrow()
            assertEquals("splits/base-master_2.apk", for28.base.zipEntryName)
            assertEquals(2, for28.splits.size)
            assertTrue(for28.splits.all { it.zipEntryName!!.endsWith("_2.apk") })

            val for34 = ApkInspector.selectVariant(inspected, 34).getOrThrow()
            assertEquals("splits/base-master_3.apk", for34.base.zipEntryName)
            assertTrue(for34.splits.all { it.zipEntryName!!.endsWith("_3.apk") })

            // A TV older than every variant floor cannot install the file.
            assertEquals(InstallError.OlderSdk, ApkInspector.selectVariant(inspected, 19).installErrorOrNull())
        }
    }

    @Test
    fun selects_variant_from_toc_alone_without_manifest_floors() {
        // No manifest carries minSdkVersion, so only the toc.pb floors can resolve this.
        val archive = buildMultiVariantApks(manifestMinSdks = listOf(null, null, null))
        TempZipInstallSource.fromBytes("multi.apks", archive).use { source ->
            val inspected = ApkInspector.inspect(source).getOrThrow()
            assertEquals(listOf(21, 28, 33), inspected.variants?.map { it.minSdk })
            val for28 = ApkInspector.selectVariant(inspected, 28).getOrThrow()
            assertEquals("splits/base-master_2.apk", for28.base.zipEntryName)
        }
    }

    @Test
    fun falls_back_to_suffix_grouping_when_toc_is_missing_or_unreadable() {
        val fullToc = buildTocBytes(
            listOf(
                TocVariantSpec(21, listOf("splits/base-master.apk")),
                TocVariantSpec(28, listOf("splits/base-master_2.apk")),
                TocVariantSpec(33, listOf("splits/base-master_3.apk")),
            ),
        )

        for (archive in listOf(
            buildMultiVariantApks(includeToc = false),
            buildMultiVariantApks(tocBytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x01)),
            buildMultiVariantApks(tocBytes = fullToc.copyOf(fullToc.size / 2)),
        )) {
            TempZipInstallSource.fromBytes("multi.apks", archive).use { source ->
                val inspected = ApkInspector.inspect(source).getOrThrow()
                assertEquals(3, inspected.variants?.size)
                val resolved = ApkInspector.selectVariant(inspected, 28).getOrThrow()
                assertEquals("splits/base-master_2.apk", resolved.base.zipEntryName)
            }
        }
    }

    @Test
    fun multi_variant_version_name_comes_from_the_base_not_the_first_entry() {
        val archive = buildMultiVariantApks(bundletoolOrder = true)
        TempZipInstallSource.fromBytes("multi.apks", archive).use { source ->
            val inspected = ApkInspector.inspect(source).getOrThrow()
            assertEquals("5.0.0", inspected.versionName)
            assertEquals("5.0.0", ApkInspector.selectVariant(inspected, 28).getOrThrow().versionName)
        }
    }

    @Test
    fun suffix_fallback_keeps_x86_64_splits_in_their_own_variant() {
        // "base-x86_64.apk" ends in "_64" but belongs to the first variant, not a variant "64".
        val archive = buildMultiVariantApks(includeToc = false, includeX86_64 = true)
        TempZipInstallSource.fromBytes("multi.apks", archive).use { source ->
            val inspected = ApkInspector.inspect(source).getOrThrow()
            assertEquals(listOf(21, 28, 33), inspected.variants?.map { it.minSdk })
            val for21 = ApkInspector.selectVariant(inspected, 21).getOrThrow()
            assertEquals("splits/base-master.apk", for21.base.zipEntryName)
            assertTrue(for21.splits.any { it.zipEntryName == "splits/base-x86_64.apk" })
            val for28 = ApkInspector.selectVariant(inspected, 28).getOrThrow()
            assertTrue(for28.splits.any { it.zipEntryName == "splits/base-x86_64_2.apk" })
            assertEquals(3, for28.splits.size)
        }
    }

    @Test
    fun refuses_multi_variant_bundle_without_a_safe_choice() {
        // Several bases with no toc and no bundletool suffixes: nothing to choose with.
        val ambiguous = buildZipBytes(
            listOf(
                "splits/base-one.apk" to buildApkBytes(AxmlSpec(packageName = "com.example.multi", versionCode = 1)),
                "splits/base-two.apk" to buildApkBytes(AxmlSpec(packageName = "com.example.multi", versionCode = 1)),
            ),
        )
        TempZipInstallSource.fromBytes("ambiguous.apks", ambiguous).use {
            assertEquals(InstallError.MixedBundle, ApkInspector.inspect(it).installErrorOrNull())
        }

        // Garbage toc, no suffixes, no manifest floors: the fallback also has nothing to use.
        val garbageToc = buildZipBytes(
            listOf(
                "toc.pb" to byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x01),
                "splits/base-one.apk" to buildApkBytes(AxmlSpec(packageName = "com.example.multi", versionCode = 1)),
                "splits/base-two.apk" to buildApkBytes(AxmlSpec(packageName = "com.example.multi", versionCode = 1)),
            ),
        )
        TempZipInstallSource.fromBytes("garbage.apks", garbageToc).use {
            assertEquals(InstallError.MixedBundle, ApkInspector.inspect(it).installErrorOrNull())
        }

        // Two variants sharing one floor stay ambiguous even for a TV that matches it.
        TempZipInstallSource.fromBytes("tied.apks", buildMultiVariantApks(minSdks = listOf(21, 28, 28))).use { source ->
            val inspected = ApkInspector.inspect(source).getOrThrow()
            assertEquals(InstallError.MixedBundle, ApkInspector.selectVariant(inspected, 28).installErrorOrNull())
        }
    }

    @Test
    fun strips_iso_control_characters_from_display_labels() {
        val apkBytes = buildApkBytes(
            AxmlSpec(
                packageName = "com.example.controls",
                versionCode = 1,
                appLabelLiteral = "Bad\u0007Label\u007F",
            ),
        )
        ByteArrayInstallSource("Controls.apk", apkBytes).use {
            assertEquals("Bad Label", ApkInspector.inspect(it).getOrThrow().displayLabel)
        }

        // A NUL reaches the filename-stem fallback when the manifest carries no label.
        val noLabelBytes = buildApkBytes(
            AxmlSpec(packageName = "com.example.controls", versionCode = 1),
        )
        ByteArrayInstallSource("A\u0000B.apk", noLabelBytes).use {
            assertEquals("A B", ApkInspector.inspect(it).getOrThrow().displayLabel)
        }
    }
}

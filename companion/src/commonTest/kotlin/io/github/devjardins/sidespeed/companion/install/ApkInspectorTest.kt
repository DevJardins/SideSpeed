package io.github.devjardins.sidespeed.companion.install

import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.AxmlSpec
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.ByteArrayInstallSource
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.TempZipInstallSource
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.buildApkBytes
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.buildAxml
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.buildZipBytes
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

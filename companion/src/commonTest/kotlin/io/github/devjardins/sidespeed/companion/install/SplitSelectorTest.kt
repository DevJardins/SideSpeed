package io.github.devjardins.sidespeed.companion.install

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitSelectorTest {
    private fun meta(
        zipName: String?,
        sizeBytes: Long,
        splitName: String? = null,
        configForSplit: String? = null,
        isFeatureSplit: Boolean = false,
    ) = ApkEntryMeta(
        zipEntryName = zipName,
        sizeBytes = sizeBytes,
        manifest = ApkManifestInfo(
            packageName = "com.example.app",
            versionCode = 100L,
            versionName = "1.0.0",
            splitName = splitName,
            configForSplit = configForSplit,
            isFeatureSplit = isFeatureSplit,
            literalLabel = "Example",
        ),
    )

    private fun bundle(base: ApkEntryMeta, vararg splits: ApkEntryMeta) = InspectedPackage(
        packageName = "com.example.app",
        displayLabel = "Example",
        versionCode = 100L,
        versionName = "1.0.0",
        isBundle = true,
        base = base,
        splits = splits.toList(),
    )

    @Test
    fun selects_highest_priority_abi_and_closest_density_and_locale_plus_feature_splits() {
        val base = meta("splits/base-master.apk", 10_000L)
        val arm64 = meta("splits/base-arm64_v8a.apk", 4_000L, splitName = "config.arm64_v8a")
        val armv7 = meta("splits/base-armeabi_v7a.apk", 3_500L, splitName = "config.armeabi_v7a")
        val x86 = meta("splits/base-x86_64.apk", 4_500L, splitName = "config.x86_64")
        val mdpi = meta("splits/base-mdpi.apk", 1_000L, splitName = "config.mdpi")
        val xhdpi = meta("splits/base-xhdpi.apk", 2_000L, splitName = "config.xhdpi")
        val xxhdpi = meta("splits/base-xxhdpi.apk", 3_000L, splitName = "config.xxhdpi")
        val en = meta("splits/base-en.apk", 500L, splitName = "config.en")
        val fr = meta("splits/base-fr.apk", 550L, splitName = "config.fr")
        val de = meta("splits/base-de.apk", 520L, splitName = "config.de")
        val feature = meta("splits/feature_tv.apk", 2_500L, splitName = "feature_tv", isFeatureSplit = true)
        val featureArm64 = meta(
            "splits/feature_tv-arm64_v8a.apk",
            1_500L,
            splitName = "feature_tv.config.arm64_v8a",
            configForSplit = "feature_tv",
        )
        val featureArmv7 = meta(
            "splits/feature_tv-armeabi_v7a.apk",
            1_400L,
            splitName = "feature_tv.config.armeabi_v7a",
            configForSplit = "feature_tv",
        )

        val pkg = bundle(
            base,
            armv7, arm64, x86,
            mdpi, xhdpi, xxhdpi,
            en, fr, de,
            feature, featureArmv7, featureArm64,
        )
        val device = DeviceProfile(
            abiList = listOf("arm64-v8a", "armeabi-v7a"),
            densityDpi = 320,
            localeTag = "fr-CA",
        )

        val selection = SplitSelector.select(pkg, device).getOrThrow()
        assertEquals(
            listOf(
                "splits/base-master.apk",
                "splits/base-arm64_v8a.apk",
                "splits/base-xhdpi.apk",
                "splits/base-fr.apk",
                "splits/feature_tv.apk",
                "splits/feature_tv-arm64_v8a.apk",
            ),
            selection.splits.map { it.entry.zipEntryName },
        )
        assertEquals(
            listOf("base.apk", "split_1.apk", "split_2.apk", "split_3.apk", "split_4.apk", "split_5.apk"),
            selection.splits.map { it.safeName },
        )
        assertEquals(10_000L + 4_000L + 2_000L + 550L + 2_500L + 1_500L, selection.totalBytes)
        assertTrue("arm64-v8a" in selection.summaryTags)
        assertTrue("xhdpi" in selection.summaryTags)
        assertTrue("fr" in selection.summaryTags)
    }

    @Test
    fun rejects_incompatible_abi_bundle() {
        val base = meta("base.apk", 1_000L)
        val x8664 = meta("config.x86_64.apk", 2_000L, splitName = "config.x86_64")
        val pkg = bundle(base, x8664)
        val armTv = DeviceProfile(
            abiList = listOf("arm64-v8a", "armeabi-v7a"),
            densityDpi = 320,
            localeTag = "en-US",
        )

        val result = SplitSelector.select(pkg, armTv)
        assertTrue(result.isFailure)
        assertEquals(InstallError.IncompatibleAbi, result.installErrorOrNull())
    }

    @Test
    fun falls_back_to_en_locale_and_highest_available_density_when_exact_missing() {
        val base = meta("base.apk", 1_000L)
        val hdpi = meta("config.hdpi.apk", 300L, splitName = "config.hdpi")
        val mdpi = meta("config.mdpi.apk", 200L, splitName = "config.mdpi")
        val de = meta("config.de.apk", 100L, splitName = "config.de")
        val en = meta("config.en.apk", 120L, splitName = "config.en")
        val pkg = bundle(base, mdpi, hdpi, de, en)

        val device = DeviceProfile(
            abiList = listOf("arm64-v8a"),
            densityDpi = 480,
            localeTag = "ja-JP",
        )
        val selection = SplitSelector.select(pkg, device).getOrThrow()
        assertEquals(
            listOf("base.apk", "config.hdpi.apk", "config.en.apk"),
            selection.splits.map { it.entry.zipEntryName },
        )

        // When TV density sits between available buckets, picks smallest >= TV density.
        val midSelection = SplitSelector.select(
            bundle(base, mdpi, meta("config.xxhdpi.apk", 400L, splitName = "config.xxhdpi")),
            DeviceProfile(abiList = listOf("arm64-v8a"), densityDpi = 213, localeTag = "en-US"),
        ).getOrThrow()
        assertEquals(listOf("base.apk", "config.xxhdpi.apk"), midSelection.splits.map { it.entry.zipEntryName })

        // When neither TV locale nor "en" is present, picks first available locale split.
        val firstLocaleSelection = SplitSelector.select(
            bundle(base, de, meta("config.es.apk", 110L, splitName = "config.es")),
            DeviceProfile(abiList = listOf("arm64-v8a"), densityDpi = 320, localeTag = "ja-JP"),
        ).getOrThrow()
        assertEquals(listOf("base.apk", "config.de.apk"), firstLocaleSelection.splits.map { it.entry.zipEntryName })
    }

    @Test
    fun rejects_selected_total_over_2gib() {
        val base = meta("base.apk", ApkInspector.MAX_INSTALL_BYTES)
        val extra = meta("config.arm64_v8a.apk", 1L, splitName = "config.arm64_v8a")
        val pkg = bundle(base, extra)
        val device = DeviceProfile(abiList = listOf("arm64-v8a"), densityDpi = 320, localeTag = "en-US")

        val result = SplitSelector.select(pkg, device)
        assertTrue(result.isFailure)
        assertEquals(InstallError.TooLarge, result.installErrorOrNull())
    }

    @Test
    fun selects_single_apk_and_handles_region_locale_x86_64_and_universal_density() {
        val single = InspectedPackage(
            packageName = "com.example.single",
            displayLabel = "Single",
            versionCode = 1L,
            versionName = "1.0",
            isBundle = false,
            base = meta(null, 5_000L),
            splits = emptyList(),
        )
        val singleSel = SplitSelector.select(
            single,
            DeviceProfile(abiList = listOf("x86_64"), densityDpi = 320, localeTag = "en-US"),
        ).getOrThrow()
        assertEquals(listOf("base.apk"), singleSel.splits.map { it.safeName })
        assertEquals(5_000L, singleSel.totalBytes)
        assertTrue(singleSel.summaryTags.isEmpty())

        val base = meta("base.apk", 1_000L)
        val x8664 = meta("config.x86_64.apk", 2_000L, splitName = "config.x86_64")
        val nodpi = meta("config.nodpi.apk", 300L, splitName = "config.nodpi")
        val fr = meta("config.fr.apk", 100L, splitName = "config.fr")
        val frCa = meta("config.fr_rCA.apk", 120L, splitName = "config.fr_rCA")
        val srLatn = meta("config.b+sr+Latn.apk", 130L, splitName = "config.b+sr+Latn")
        val pkg = bundle(base, x8664, nodpi, fr, frCa, srLatn)

        val frCaSel = SplitSelector.select(
            pkg,
            DeviceProfile(abiList = listOf("x86_64"), densityDpi = 320, localeTag = "fr-CA"),
        ).getOrThrow()
        assertEquals(
            listOf("base.apk", "config.x86_64.apk", "config.nodpi.apk", "config.fr_rCA.apk"),
            frCaSel.splits.map { it.entry.zipEntryName },
        )
        assertEquals(listOf("x86_64", "nodpi", "fr-CA"), frCaSel.summaryTags)

        val srSel = SplitSelector.select(
            pkg,
            DeviceProfile(abiList = listOf("x86_64"), densityDpi = 320, localeTag = "sr-Latn"),
        ).getOrThrow()
        assertEquals(
            listOf("base.apk", "config.x86_64.apk", "config.nodpi.apk", "config.b+sr+Latn.apk"),
            srSel.splits.map { it.entry.zipEntryName },
        )
    }
}

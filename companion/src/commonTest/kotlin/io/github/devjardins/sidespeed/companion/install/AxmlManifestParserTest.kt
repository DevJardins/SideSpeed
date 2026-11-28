package io.github.devjardins.sidespeed.companion.install

import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.AxmlSpec
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.buildAxml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AxmlManifestParserTest {
    @Test
    fun parses_utf16_and_utf8_string_pools_and_64bit_version_code() {
        val utf16Bytes = buildAxml(
            AxmlSpec(
                packageName = "com.example.video",
                versionCode = 0x80000001u.toInt(),
                versionCodeMajor = 3,
                versionName = "4.2.0",
                splitName = "feature_tv.config.arm64_v8a",
                configForSplit = "feature_tv",
                isFeatureSplit = false,
                appLabelLiteral = "Café TV",
                nestedActivityLabel = "Wrong Activity Label",
                utf8 = false,
            ),
        )
        val utf16Parsed = AxmlManifestParser.parse(utf16Bytes)!!
        assertEquals("com.example.video", utf16Parsed.packageName)
        assertEquals((3L shl 32) or 0x80000001L, utf16Parsed.versionCode)
        assertEquals("4.2.0", utf16Parsed.versionName)
        assertEquals("feature_tv.config.arm64_v8a", utf16Parsed.splitName)
        assertEquals("feature_tv", utf16Parsed.configForSplit)
        assertFalse(utf16Parsed.isFeatureSplit)
        assertEquals("Café TV", utf16Parsed.literalLabel)

        val longVersionName = "1.0-" + "x".repeat(140)
        val utf8Bytes = buildAxml(
            AxmlSpec(
                packageName = "com.example.utf8",
                versionCode = 0xFFFFFFFEu.toInt(),
                versionCodeMajor = 1,
                versionName = longVersionName,
                splitName = "feature_tv",
                isFeatureSplit = true,
                appLabelResId = 0x7f0e0001,
                utf8 = true,
            ),
        )
        val utf8Parsed = AxmlManifestParser.parse(utf8Bytes)!!
        assertEquals("com.example.utf8", utf8Parsed.packageName)
        assertEquals((1L shl 32) or 0xFFFFFFFEL, utf8Parsed.versionCode)
        assertEquals(longVersionName, utf8Parsed.versionName)
        assertEquals("feature_tv", utf8Parsed.splitName)
        assertNull(utf8Parsed.configForSplit)
        assertTrue(utf8Parsed.isFeatureSplit)
        assertNull(utf8Parsed.literalLabel)

        val obfuscatedAttrNames = buildAxml(
            AxmlSpec(
                packageName = "org.example.shrunk",
                versionCode = 42,
                versionName = "2.0",
                isFeatureSplit = true,
                appLabelLiteral = "Shrunk App",
                utf8 = true,
                obfuscateResourceAttrNames = true,
            ),
        )
        val shrunkParsed = AxmlManifestParser.parse(obfuscatedAttrNames)!!
        assertEquals("org.example.shrunk", shrunkParsed.packageName)
        assertEquals(42L, shrunkParsed.versionCode)
        assertEquals("2.0", shrunkParsed.versionName)
        assertTrue(shrunkParsed.isFeatureSplit)
        assertEquals("Shrunk App", shrunkParsed.literalLabel)
    }

    @Test
    fun captures_label_resource_id_for_reference_labels() {
        val withRef = buildAxml(
            AxmlSpec(packageName = "com.example.app", versionCode = 1, appLabelResId = 0x7f010000),
        )
        val parsed = AxmlManifestParser.parse(withRef)!!
        assertNull(parsed.literalLabel)
        assertEquals(0x7f010000, parsed.labelResId)

        val withLiteral = buildAxml(
            AxmlSpec(packageName = "com.example.app", versionCode = 1, appLabelLiteral = "Literal App"),
        )
        val literalParsed = AxmlManifestParser.parse(withLiteral)!!
        assertEquals("Literal App", literalParsed.literalLabel)
        assertNull(literalParsed.labelResId)
    }

    @Test
    fun rejects_truncated_or_non_axml_bytes() {
        assertNull(AxmlManifestParser.parse(byteArrayOf()))
        assertNull(AxmlManifestParser.parse("<manifest package=\"com.example.app\"/>".encodeToByteArray()))
        assertNull(AxmlManifestParser.parse(byteArrayOf(0x03, 0x00, 0x08, 0x00, 0x08, 0x00, 0x00, 0x00)))

        val valid = buildAxml(AxmlSpec(packageName = "com.example.app", versionCode = 1))
        for (len in 1 until minOf(valid.size, 80)) {
            assertNull(AxmlManifestParser.parse(valid.copyOf(len)), "Should reject truncated AXML at $len bytes")
        }

        val missingVersion = buildAxml(AxmlSpec(packageName = "com.example.app", versionCode = null))
        assertNull(AxmlManifestParser.parse(missingVersion))

        val missingPackage = buildAxml(AxmlSpec(includePackage = false, versionCode = 1))
        assertNull(AxmlManifestParser.parse(missingPackage))
    }

    @Test
    fun parses_uses_sdk_min_sdk_version() {
        val withMin = buildAxml(AxmlSpec(packageName = "com.example.app", versionCode = 1, minSdkVersion = 28))
        assertEquals(28, AxmlManifestParser.parse(withMin)!!.minSdkVersion)

        val without = buildAxml(AxmlSpec(packageName = "com.example.app", versionCode = 1))
        assertNull(AxmlManifestParser.parse(without)!!.minSdkVersion)
    }

    @Test
    fun rejects_invalid_package_name() {
        val badPackages = listOf(
            "",
            "app",
            " com.example.app",
            "com.example.app ",
            "com..example",
            "com.1bad",
            "com.example;id",
            "a." + "b".repeat(255),
        )
        for (bad in badPackages) {
            val bytes = buildAxml(AxmlSpec(packageName = bad, versionCode = 1))
            assertNull(AxmlManifestParser.parse(bytes), "Should reject invalid package '$bad'")
        }
    }
}

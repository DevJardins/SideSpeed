package io.github.devjardins.sidespeed.companion.install

import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.ArscEncoding
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.ArscEntrySpec
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.ArscValue
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures.buildArsc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArscLabelResolverTest {
    private fun labelArsc(vararg entries: ArscEntrySpec, utf8: Boolean = false): ByteArray =
        buildArsc(entries.toList(), utf8 = utf8)

    @Test
    fun resolves_default_config_label() {
        val arsc = labelArsc(
            ArscEntrySpec("app_name", default = ArscValue.Str("Resolved App")),
        )
        assertEquals("Resolved App", ArscLabelResolver.resolve(arsc, 0x7f010000))
    }

    @Test
    fun prefers_default_over_english_and_other_configs() {
        val arsc = labelArsc(
            ArscEntrySpec(
                "app_name",
                default = ArscValue.Str("Default Name"),
                en = ArscValue.Str("English Name"),
                other = ArscValue.Str("Other Name"),
            ),
        )
        assertEquals("Default Name", ArscLabelResolver.resolve(arsc, 0x7f010000))
    }

    @Test
    fun falls_back_to_english_then_any_config() {
        val englishOnly = labelArsc(
            ArscEntrySpec(
                "app_name",
                en = ArscValue.Str("English Name"),
                other = ArscValue.Str("Other Name"),
            ),
        )
        assertEquals("English Name", ArscLabelResolver.resolve(englishOnly, 0x7f010000))

        val otherOnly = labelArsc(
            ArscEntrySpec("app_name", other = ArscValue.Str("Other Name")),
        )
        assertEquals("Other Name", ArscLabelResolver.resolve(otherOnly, 0x7f010000))
    }

    @Test
    fun follows_reference_to_reference() {
        val arsc = labelArsc(
            ArscEntrySpec("app_name", default = ArscValue.Ref(1)),
            ArscEntrySpec("alias", default = ArscValue.Ref(2)),
            ArscEntrySpec("real", default = ArscValue.Str("Deep Name")),
        )
        assertEquals("Deep Name", ArscLabelResolver.resolve(arsc, 0x7f010000))

        val tooDeep = labelArsc(
            ArscEntrySpec("app_name", default = ArscValue.Ref(1)),
            ArscEntrySpec("one", default = ArscValue.Ref(2)),
            ArscEntrySpec("two", default = ArscValue.Ref(3)),
            ArscEntrySpec("three", default = ArscValue.Str("Too Deep")),
        )
        assertNull(ArscLabelResolver.resolve(tooDeep, 0x7f010000))
    }

    @Test
    fun resolves_labels_in_non_string_type_ids() {
        // Real apps keep the label outside type 1 (the debug companion APK uses type 10).
        val arsc = buildArsc(
            listOf(
                ArscEntrySpec("style", default = ArscValue.Raw(type = 0x10, data = 1)),
                ArscEntrySpec("app_name", default = ArscValue.Str("Typed Label")),
            ),
            typeId = 10,
        )
        assertEquals("Typed Label", ArscLabelResolver.resolve(arsc, 0x7f0a0001))
        assertNull(ArscLabelResolver.resolve(arsc, 0x7f010001))
    }

    @Test
    fun returns_null_for_unknown_or_non_string_values() {
        val arsc = labelArsc(
            ArscEntrySpec("app_name", default = ArscValue.Str("Resolved App")),
            ArscEntrySpec("count", default = ArscValue.Raw(type = 0x10, data = 7)),
        )
        assertNull(ArscLabelResolver.resolve(arsc, 0x7f010005))
        assertNull(ArscLabelResolver.resolve(arsc, 0x7f010001))
        assertNull(ArscLabelResolver.resolve(arsc, 0x7f020000))
        assertNull(ArscLabelResolver.resolve(arsc, 0))
    }

    @Test
    fun resolves_utf8_string_pools() {
        val arsc = labelArsc(
            ArscEntrySpec("app_name", default = ArscValue.Str("Café App")),
            utf8 = true,
        )
        assertEquals("Café App", ArscLabelResolver.resolve(arsc, 0x7f010000))
    }

    @Test
    fun returns_null_for_truncated_or_garbage_arsc() {
        assertNull(ArscLabelResolver.resolve(byteArrayOf(), 0x7f010000))
        assertNull(ArscLabelResolver.resolve("not an arsc".encodeToByteArray(), 0x7f010000))

        val valid = labelArsc(ArscEntrySpec("app_name", default = ArscValue.Str("Resolved App")))
        assertNull(ArscLabelResolver.resolve(valid.copyOf(valid.size / 2), 0x7f010000))

        val badMagic = valid.copyOf()
        badMagic[0] = 0x05
        badMagic[1] = 0x00
        assertNull(ArscLabelResolver.resolve(badMagic, 0x7f010000))

        val hugePackageCount = valid.copyOf()
        putU32Le(hugePackageCount, 8, 99)
        assertNull(ArscLabelResolver.resolve(hugePackageCount, 0x7f010000))

        // First global-pool string offset points outside the pool.
        val badStringOffset = valid.copyOf()
        putU32Le(badStringOffset, 12 + 28, 0x7FFFFF00)
        assertNull(ArscLabelResolver.resolve(badStringOffset, 0x7f010000))
    }

    @Test
    fun returns_null_for_huge_entry_counts() {
        // One entry past the per-type cap. The target value is present, so only the
        // cap itself can explain the fallback.
        val manyEntries = (0 until 200_001).map {
            if (it == 0) ArscEntrySpec("app_name", default = ArscValue.Str("Resolved App"))
            else ArscEntrySpec("k$it")
        }
        assertNull(ArscLabelResolver.resolve(buildArsc(manyEntries), 0x7f010000))
    }

    private fun putU32Le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    @Test
    fun resolves_sparse_offset16_and_compact_type_chunks() {
        val entries = listOf(
            ArscEntrySpec("other_one", default = ArscValue.Str("Wrong One")),
            ArscEntrySpec("other_two", en = ArscValue.Str("Wrong Two")),
            ArscEntrySpec("app_name", default = ArscValue.Str("Packed App")),
            ArscEntrySpec("alias", default = ArscValue.Ref(2)),
        )
        for (encoding in ArscEncoding.entries) {
            val arsc = buildArsc(entries, encoding = encoding)
            assertEquals("Packed App", ArscLabelResolver.resolve(arsc, 0x7f010002), "direct, $encoding")
            assertEquals("Packed App", ArscLabelResolver.resolve(arsc, 0x7f010003), "reference, $encoding")
            assertEquals("Wrong Two", ArscLabelResolver.resolve(arsc, 0x7f010001), "english only, $encoding")
            assertNull(ArscLabelResolver.resolve(arsc, 0x7f010009), "missing, $encoding")
        }
    }
}

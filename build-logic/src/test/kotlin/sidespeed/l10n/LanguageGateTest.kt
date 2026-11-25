package sidespeed.l10n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LanguageGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    companion object {
        private const val D = "$"
        private val ENTRIES = linkedMapOf(
            "app_a" to """<string name="app_a">Alpha</string>""",
            "app_b" to """<string name="app_b">Beta</string>""",
            "app_c" to """<string name="app_c">Gamma</string>""",
            "app_d" to """<string name="app_d">Delta</string>""",
            "app_e" to """<string name="app_e">Epsilon</string>""",
            "app_f" to """<string name="app_f">Zeta</string>""",
            "app_g" to """<string name="app_g">Eta</string>""",
            "app_h" to """<string name="app_h">Theta</string>""",
            "fmt" to """<string name="fmt">Hello %1${D}s, you have %2${D}d messages</string>""",
            "items" to """<plurals name="items"><item quantity="one">%1${D}d item</item><item quantity="other">%1${D}d items</item></plurals>""",
        )
        private const val UNTRANSLATABLE =
            """<string name="debug_only" translatable="false">Debug</string>"""
        // French needs one/many/other; a complete French translation covers all three.
        private const val FR_PLURALS =
            """<plurals name="items"><item quantity="one">%1${D}d article</item><item quantity="many">beaucoup d'articles</item><item quantity="other">%1${D}d articles</item></plurals>"""
        private val ENGLISH =
            "<resources>\n" + (ENTRIES.values + UNTRANSLATABLE).joinToString("\n") + "\n</resources>\n"
        private const val BAR = 0.7
    }

    private fun translationXml(
        names: Collection<String>,
        overrides: Map<String, String> = emptyMap(),
        extras: String = "",
    ): String {
        val body = names.map { overrides[it] ?: ENTRIES.getValue(it) } + listOfNotNull(
            extras.ifEmpty { null },
        )
        return "<resources>\n" + body.joinToString("\n") + "\n</resources>\n"
    }

    private fun layout(
        translations: Map<String, String>,
        english: String = ENGLISH,
    ): Pair<File, File> {
        val root = tmp.newFolder()
        val englishXml = File(root, "english.xml").apply { writeText(english) }
        val res = File(root, "res").apply { mkdir() }
        File(res, "values").apply { mkdir() }.resolve("strings.xml").apply { writeText(english) }
        for ((folder, xml) in translations) {
            File(res, folder).apply { mkdir() }.resolve("strings.xml").apply { writeText(xml) }
        }
        return englishXml to res
    }

    private fun evaluate(
        translations: Map<String, String>,
        bar: Double = BAR,
    ): GateResult {
        val (englishXml, resDir) = layout(translations)
        return LanguageGate.evaluate(englishXml, resDir, bar)
    }

    @Test
    fun complete_language_is_in() {
        val xml = translationXml(ENTRIES.keys, mapOf("items" to FR_PLURALS))
        val result = evaluate(mapOf("values-fr" to xml))
        assertTrue(result.included.any { it.tag == "fr" })
        val fr = result.included.single { it.tag == "fr" }
        assertEquals(10, fr.translated)
        assertEquals(10, fr.total)
    }

    @Test
    fun missing_key_counts_against() {
        val out = evaluate(mapOf("values-fr" to translationXml(ENTRIES.keys.take(6))))
        assertTrue(out.included.none { it.tag == "fr" })
        val fr = out.excluded.single { it.tag == "fr" }
        assertEquals(0.6, fr.share, 0.0)
        val backIn = evaluate(mapOf("values-fr" to translationXml(ENTRIES.keys.take(7))))
        assertTrue(backIn.included.any { it.tag == "fr" })
    }

    @Test
    fun missing_plural_quantity_counts_against() {
        val partialPlurals =
            """<plurals name="items"><item quantity="one">wahid</item><item quantity="other">kathir</item></plurals>"""
        val result = evaluate(
            mapOf("values-ar" to translationXml(ENTRIES.keys, mapOf("items" to partialPlurals))),
        )
        val ar = (result.included + result.excluded).single { it.tag == "ar" }
        assertEquals(10, ar.total)
        assertEquals(9, ar.translated)
    }

    @Test
    fun stale_key_ignored() {
        val xml = translationXml(
            ENTRIES.keys,
            mapOf("items" to FR_PLURALS),
            extras = """<string name="nope">Nope</string>""",
        )
        val result = evaluate(mapOf("values-fr" to xml))
        val fr = result.included.single { it.tag == "fr" }
        assertEquals(10, fr.total)
        assertEquals(10, fr.translated)
    }

    @Test
    fun untranslatable_key_ignored() {
        val xml = translationXml(
            ENTRIES.keys,
            mapOf("items" to FR_PLURALS),
            extras = """<string name="debug_only">Debugue</string>""",
        )
        val result = evaluate(mapOf("values-fr" to xml))
        val fr = result.included.single { it.tag == "fr" }
        assertEquals(10, fr.total)
        assertEquals(10, fr.translated)
    }

    @Test
    fun placeholder_mismatch_counts_against() {
        val missingArg = mapOf(
            "items" to FR_PLURALS,
            "fmt" to """<string name="fmt">Bonjour %1${D}s</string>""",
        )
        val missing = evaluate(mapOf("values-fr" to translationXml(ENTRIES.keys, missingArg)))
        assertEquals(9, (missing.included + missing.excluded).single { it.tag == "fr" }.translated)

        val wrongType = mapOf(
            "items" to FR_PLURALS,
            "fmt" to """<string name="fmt">Bonjour %1${D}d, vous avez %2${D}d messages</string>""",
        )
        val wrong = evaluate(mapOf("values-fr" to translationXml(ENTRIES.keys, wrongType)))
        assertEquals(9, (wrong.included + wrong.excluded).single { it.tag == "fr" }.translated)
    }

    @Test
    fun empty_value_counts_against() {
        val empty = mapOf(
            "items" to FR_PLURALS,
            "app_a" to """<string name="app_a"></string>""",
        )
        val result = evaluate(mapOf("values-fr" to translationXml(ENTRIES.keys, empty)))
        assertEquals(9, (result.included + result.excluded).single { it.tag == "fr" }.translated)
    }

    @Test
    fun malformed_file_left_out_not_failing() {
        val result = evaluate(mapOf("values-de" to "<resources><string"))
        val de = result.excluded.single { it.tag == "de" }
        assertEquals(0, de.translated)
    }

    @Test
    fun doctype_file_left_out_not_resolved() {
        val xml = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/hosts\">]><resources><string name=\"a\">&x;</string></resources>"
        val result = evaluate(mapOf("values-de" to xml))
        assertEquals(0, result.excluded.single { it.tag == "de" }.translated)
    }

    @Test
    fun tag_to_filter_round_trips_folder_names() {
        assertEquals("fr", LanguageGate.tagToFilter("fr"))
        assertEquals("pt-rBR", LanguageGate.tagToFilter("pt-BR"))
        assertEquals("zh-rTW", LanguageGate.tagToFilter("zh-TW"))
        assertEquals("b+sr+Latn", LanguageGate.tagToFilter("sr-Latn"))
        assertEquals("b+sr+Latn+RS", LanguageGate.tagToFilter("sr-Latn-RS"))
        assertEquals("es-r419", LanguageGate.tagToFilter("es-419"))
    }

    @Test
    fun release_filters_are_english_and_shipped_languages_only() {
        val result = GateResult(
            included = listOf(LanguageScore("fr", 10, 10), LanguageScore("pt-BR", 8, 10)),
            excluded = listOf(LanguageScore("ar", 1, 10)),
        )
        assertEquals(listOf("en", "fr", "pt-rBR"), LanguageGate.releaseLocaleFilters(result))
    }

    @Test
    fun pseudo_locale_filters_are_kept_apart_for_debug() {
        assertEquals(listOf("en-rXA", "ar-rXB"), LanguageGate.PSEUDO_LOCALE_FILTERS)
    }

    @Test
    fun qualifier_to_tag() {
        assertEquals("fr", LanguageGate.qualifierToTag("values-fr"))
        assertEquals("pt-BR", LanguageGate.qualifierToTag("values-pt-rBR"))
        assertEquals("zh-TW", LanguageGate.qualifierToTag("values-zh-rTW"))
        assertEquals("sr-Latn", LanguageGate.qualifierToTag("values-b+sr+Latn"))
        assertNull(LanguageGate.qualifierToTag("values-night"))
        assertNull(LanguageGate.qualifierToTag("values"))
    }

    @Test
    fun summary_lists_in_and_out() {
        val result = GateResult(
            included = listOf(LanguageScore("ar", 144, 175)),
            excluded = listOf(LanguageScore("fr", 132, 330)),
        )
        assertEquals(
            "SideSpeed languages (app): bar 70%\n" +
                "  in:  ar 82% (31 missing)\n" +
                "  out: fr 40% (198 missing)",
            LanguageGate.summary("app", result),
        )
    }

    @Test
    fun summary_empty_reports_english_only() {
        assertEquals(
            "SideSpeed languages (app): bar 70%, English only",
            LanguageGate.summary("app", GateResult(emptyList(), emptyList())),
        )
    }
}

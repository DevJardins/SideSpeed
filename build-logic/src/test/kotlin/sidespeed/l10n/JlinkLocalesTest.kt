package sidespeed.l10n

import org.junit.Assert.assertEquals
import org.junit.Test

class JlinkLocalesTest {

    @Test
    fun include_locales_is_english_plus_shipped_tags() {
        assertEquals("--include-locales=en,ar,fr", includeLocalesArg(listOf("ar", "fr")))
    }

    @Test
    fun include_locales_with_no_shipped_languages_is_english_only() {
        assertEquals("--include-locales=en", includeLocalesArg(emptyList()))
    }
}

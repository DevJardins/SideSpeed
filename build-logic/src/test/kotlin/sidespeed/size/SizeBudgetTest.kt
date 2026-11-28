package sidespeed.size

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeBudgetTest {
    private val apk = Budget(key = "tvApk", dir = "app/build/outputs/apk/release", extension = "apk", maxBytes = 9 * SizeBudget.MIB)
    private val deb = Budget(key = "deb", dir = "deb", extension = "deb", maxBytes = 74 * SizeBudget.MIB)

    @Test
    fun files_within_budget_pass() {
        val found = mapOf("tvApk" to listOf("app-release.apk" to 8_251_672L), "deb" to listOf("a.deb" to 69_755_606L))
        assertEquals(emptyList<String>(), SizeBudget.check(listOf(apk, deb), found))
    }

    @Test
    fun over_budget_file_is_reported() {
        val found = mapOf("tvApk" to listOf("app-release.apk" to 9 * SizeBudget.MIB + 1))
        val problems = SizeBudget.check(listOf(apk), found)
        assertEquals(1, problems.size)
        assertTrue(problems[0], problems[0].contains("app-release.apk") && problems[0].contains("9.0 MiB"))
    }

    @Test
    fun missing_file_is_reported() {
        val problems = SizeBudget.check(listOf(apk), emptyMap())
        assertEquals(1, problems.size)
        assertTrue(problems[0], problems[0].contains("tvApk") && problems[0].contains("app/build/outputs/apk/release"))
    }

    @Test
    fun every_file_in_a_folder_is_checked() {
        val found = mapOf("tvApk" to listOf("a.apk" to 1L, "b.apk" to 10 * SizeBudget.MIB))
        val problems = SizeBudget.check(listOf(apk), found)
        assertEquals(1, problems.size)
        assertTrue(problems[0], problems[0].contains("b.apk"))
    }

    @Test
    fun budgets_select_only_the_requested_keys() {
        assertEquals(listOf(deb), SizeBudget.select(listOf(apk, deb), "deb"))
        assertEquals(listOf(apk, deb), SizeBudget.select(listOf(apk, deb), null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknown_key_fails() {
        SizeBudget.select(listOf(apk, deb), "dmg")
    }
}

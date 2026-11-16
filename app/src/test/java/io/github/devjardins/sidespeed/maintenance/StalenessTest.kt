package io.github.devjardins.sidespeed.maintenance

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.data.LastCompile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StalenessTest {
    private fun app(updated: Long) = AppEntry("com.a.b", "A", null, updated)
    private fun last(ms: Long) = LastCompile(ms, CompileMode.SPEED)
    private fun rec(i: Int, label: String = "L", msg: String = "m") = JobRecord("com.a.p$i", label, i.toLong(), true, msg)

    @Test fun neverCompiledIsStale() = assertTrue(isStale(app(1), null))
    @Test fun updatedAfterCompileIsStale() = assertTrue(isStale(app(2000), last(1000)))
    @Test fun compiledAfterUpdateIsFresh() = assertFalse(isStale(app(1000), last(2000)))
    @Test fun equalTimesAreFresh() = assertFalse(isStale(app(1000), last(1000)))

    @Test fun appendHistoryKeepsNewest20() {
        var h = emptyList<JobRecord>()
        for (i in 1..25) h = appendHistory(h, rec(i))
        assertEquals(HISTORY_LIMIT, h.size)
        assertEquals("com.a.p25", h.first().pkg)
        assertEquals("com.a.p6", h.last().pkg)
    }

    @Test fun historyRoundTripsHostileText() {
        val list = listOf(rec(1, label = "a|b\nc%d", msg = "x|y\r\n%7C"), rec(2))
        assertEquals(list, decodeHistory(encodeHistory(list)))
    }

    @Test fun decodeSkipsMalformedLines() {
        val good = encodeHistory(listOf(rec(1)))
        assertEquals(listOf(rec(1)), decodeHistory("garbage\n$good\nx|y|z"))
    }
}

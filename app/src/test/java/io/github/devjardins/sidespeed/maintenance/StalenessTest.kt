package io.github.devjardins.sidespeed.maintenance

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.status.StatusBadge
import io.github.devjardins.sidespeed.testing.english
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

    @Test fun speedProfileBelowSpeedTargetNeedsCompile() {
        assertTrue(needsCompile(app(1), last(2000), StatusBadge.SPEED_PROFILE))
        assertTrue(needsCompile(app(1), last(2000), StatusBadge.NEEDS_RECOMPILE))
    }
    @Test fun speedBadgeAndUnknownBadgeFollowTheTimeRule() {
        assertFalse(needsCompile(app(1), last(2000), StatusBadge.SPEED_OK))
        assertFalse(needsCompile(app(1), last(2000), StatusBadge.UNKNOWN))
        assertFalse(needsCompile(app(1), last(2000), null))
        assertTrue(needsCompile(app(3000), last(2000), StatusBadge.SPEED_OK))
    }
    @Test fun speedProfileTargetOnlyRecompilesWhenBelowSpeedProfile() {
        val sp = LastCompile(2000, CompileMode.SPEED_PROFILE)
        assertFalse(needsCompile(app(1), sp, StatusBadge.SPEED_PROFILE))
        assertTrue(needsCompile(app(1), sp, StatusBadge.NEEDS_RECOMPILE))
    }

    @Test fun old_record_without_key_shows_stored_text() {
        val decoded = decodeHistory("com.a.p1|My App|12345|false|Connection lost")
        assertEquals(1, decoded.size)
        val record = decoded.single()
        assertEquals("Connection lost", record.message)
        assertEquals(null, record.messageKey)
        assertEquals(null, record.detail)
        assertEquals("Connection lost", record.display.english())
    }

    @Test fun new_record_round_trips_key_and_detail() {
        val list = listOf(
            JobRecord("com.a.p1", "App 1", 100L, true, "Success", JobMessage.SUCCESS.name, null),
            JobRecord("com.a.p2", "App 2", 200L, false, "Failure", JobMessage.FAILURE.name, null),
            JobRecord("com.a.p3", "App 3", 300L, false, "a|b\nc%d", JobMessage.ERROR.name, "a|b\nc%d"),
        )
        val decoded = decodeHistory(encodeHistory(list))
        assertEquals(list, decoded)
        assertEquals("Success", decoded[0].display.english())
        assertEquals("Failure", decoded[1].display.english())
        assertEquals("a|b\nc%d", decoded[2].display.english())
    }

    @Test fun jobMessage_entries_and_unknown_key_fallback() {
        assertEquals("Success", JobMessage.SUCCESS.text(null).english())
        assertEquals("Failure", JobMessage.FAILURE.text(null).english())
        assertEquals("Connection lost", JobMessage.CONNECTION_LOST.text(null).english())
        assertEquals("Error", JobMessage.ERROR.text(null).english())
        assertEquals("disk full", JobMessage.ERROR.text("disk full").english())
        val unknown = JobRecord("com.a.p1", "App", 10L, false, "Legacy fallback", "FUTURE_KEY", null)
        assertEquals("Legacy fallback", unknown.display.english())
    }
}

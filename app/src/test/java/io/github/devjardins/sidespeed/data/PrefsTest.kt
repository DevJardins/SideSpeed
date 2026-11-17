package io.github.devjardins.sidespeed.data

import io.github.devjardins.sidespeed.compile.CompileMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefsTest {
    @Test fun parsesStoredRecord() {
        assertEquals(LastCompile(1234L, CompileMode.SPEED), parseLastCompile("1234|speed"))
        assertEquals(LastCompile(99L, CompileMode.SPEED_PROFILE), parseLastCompile("99|speed-profile"))
    }

    @Test fun rejectsMissingAndMalformedRecords() {
        assertNull(parseLastCompile(null))
        assertNull(parseLastCompile(""))
        assertNull(parseLastCompile("1234"))
        assertNull(parseLastCompile("1234|speed|extra"))
        assertNull(parseLastCompile("not-a-number|speed"))
        assertNull(parseLastCompile("1234|profile"))
        assertNull(parseLastCompile("1234|SPEED"))
    }
}

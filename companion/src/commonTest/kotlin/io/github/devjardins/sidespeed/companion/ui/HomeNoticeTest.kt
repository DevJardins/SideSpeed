package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.install.InstallOutcome
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeNoticeTest {
    @Test fun failuresAreErrors() {
        assertTrue(noticeIsError(Copy.OPTIMIZE_FAILED))
        assertTrue(noticeIsError(Copy.NOT_TRUSTED_ERROR))
    }

    @Test fun successesAreNotErrors() {
        assertFalse(noticeIsError(Copy.OPTIMIZE_STARTED))
        assertFalse(noticeIsError(Copy.OPTIMIZE_CAUGHT_UP))
        assertFalse(noticeIsError(Copy.installed(InstallOutcome("com.example.app", "Example", compileRequested = true))))
        assertFalse(noticeIsError(Copy.installed(InstallOutcome("com.example.app", "Example", compileRequested = false, tvNeedsUpdate = true))))
        assertFalse(noticeIsError(Copy.installed(InstallOutcome("com.example.app", "Example", compileRequested = false))))
    }
}

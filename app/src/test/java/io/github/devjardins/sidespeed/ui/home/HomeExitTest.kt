package io.github.devjardins.sidespeed.ui.home

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// No TLS in this test: skip the Conscrypt provider setup (the app's
// conscrypt-android carries Android-only natives, no JVM lib).
@ConscryptMode(ConscryptMode.Mode.OFF)
class HomeExitTest {
    @Test fun homeExitIntent_isMainHomeNewTask() {
        val intent = homeExitIntent()
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_HOME))
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test fun exitToSystemHome_startsThenFinishes() {
        val order = mutableListOf<String>()
        exitToSystemHome(
            startHome = { order += "start" },
            finish = { order += "finish" },
        )
        assertEquals(listOf("start", "finish"), order)
    }

    @Test fun exitToSystemHome_finishesWhenStartThrows() {
        var finished = false
        exitToSystemHome(
            startHome = { throw RuntimeException("no home") },
            finish = { finished = true },
        )
        assertTrue(finished)
    }
}

package io.github.devjardins.sidespeed.ui.home

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeExitTest {
    @Test fun homeExitIntentSpec_isMainHomeNewTask() {
        val spec = homeExitIntentSpec()
        assertEquals(Intent.ACTION_MAIN, spec.action)
        assertEquals(Intent.CATEGORY_HOME, spec.category)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, spec.flags)
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

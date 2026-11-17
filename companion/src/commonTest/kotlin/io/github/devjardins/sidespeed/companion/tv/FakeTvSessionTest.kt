package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeTvSessionTest {
    @Test
    fun returnsScriptedTrimmedOutputAndRecordsCommands() = runTest {
        val tv = FakeTvSession(mapOf("getprop ro.serialno" to "G070VM\n", "pidof sidespeed_helper" to ""))
        val session = tv.connector().open("10.0.0.5")
        assertEquals("G070VM", session.run("getprop ro.serialno"))
        assertEquals("", session.run("pidof sidespeed_helper"))
        assertEquals(listOf("getprop ro.serialno", "pidof sidespeed_helper"), tv.sent)
        assertEquals(listOf("10.0.0.5:5555"), tv.opened)
    }

    @Test
    fun unscriptedCommandFails() = runTest {
        val tv = FakeTvSession()
        assertFailsWith<AssertionError> { tv.run("reboot") }
    }

    @Test
    fun openSwitches() = runTest {
        val tv = FakeTvSession(failOnOpen = true)
        assertFailsWith<TvUnreachable> { tv.connector().open("10.0.0.5", 5555) }
        tv.failOnOpen = false
        tv.authPending = true
        assertFailsWith<TvAuthPending> { tv.connector().open("10.0.0.5", 5555) }
        assertEquals(2, tv.opened.size)
        assertTrue(tv.sent.isEmpty())
    }

    @Test
    fun connectionLossKillsTheSession() = runTest {
        val tv = FakeTvSession(mapOf("getprop ro.serialno" to "X"), loseConnectionOn = "pidof sidespeed_helper")
        val session = tv.connector().open("10.0.0.5")
        assertFailsWith<AdbConnectionLost> { session.run("pidof sidespeed_helper") }
        assertFailsWith<AdbConnectionLost> { session.run("getprop ro.serialno") }
        assertEquals(listOf("pidof sidespeed_helper"), tv.sent)
    }

    @Test
    fun closedSessionRefusesCommands() = runTest {
        val tv = FakeTvSession(mapOf("getprop ro.serialno" to "X"))
        val session = tv.connector().open("10.0.0.5")
        session.close()
        assertFailsWith<AdbConnectionLost> { session.run("getprop ro.serialno") }
    }
}

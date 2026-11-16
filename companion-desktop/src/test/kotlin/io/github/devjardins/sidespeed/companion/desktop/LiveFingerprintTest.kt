package io.github.devjardins.sidespeed.companion.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Prints this machine's fingerprint when run with -Psidespeed.live (a manual check; skipped otherwise). */
class LiveFingerprintTest {
    @Test fun printsCurrentFingerprint() {
        assumeTrue(System.getProperty("sidespeed.live") != null)
        println("FINGERPRINT: " + runBlocking { DesktopFingerprintSource().current() })
    }
}

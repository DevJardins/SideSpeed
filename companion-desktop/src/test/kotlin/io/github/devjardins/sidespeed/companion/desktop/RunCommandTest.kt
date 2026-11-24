package io.github.devjardins.sidespeed.companion.desktop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit

class RunCommandTest {
    @Test fun outputIsReadWhileTheCommonPoolIsBusy() {
        val pool = ForkJoinPool.commonPool()
        val release = CountDownLatch(1)
        val started = CountDownLatch(pool.parallelism)
        val blockers = (1..pool.parallelism).map {
            CompletableFuture.runAsync {
                started.countDown()
                release.await(15, TimeUnit.SECONDS)
            }
        }
        try {
            check(started.await(5, TimeUnit.SECONDS)) { "common pool threads did not start" }
            assertEquals("ok", runCommand("/bin/echo", "ok").trim())
        } finally {
            release.countDown()
            blockers.forEach { it.join() }
        }
    }
}

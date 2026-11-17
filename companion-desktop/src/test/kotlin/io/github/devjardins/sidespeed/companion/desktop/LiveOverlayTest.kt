package io.github.devjardins.sidespeed.companion.desktop

import io.github.devjardins.sidespeed.companion.discovery.TailscaleStatus
import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Prints this machine's real tailnet snapshot and egress routes when run with -Dsidespeed.live (manual; skipped otherwise). */
class LiveOverlayTest {
    @Test fun printsSnapshotAndEgress() = runBlocking {
        assumeTrue(System.getProperty("sidespeed.live") != null)
        val snap = TailscaleCli().statusJson()?.let(TailscaleStatus::snapshot)
        println("SNAPSHOT: $snap")
        val egress = DesktopEgressCheck()
        for (ip in System.getProperty("sidespeed.live.ips").orEmpty().split(',').filter { it.isNotBlank() }) {
            println("EGRESS $ip: " + egress.route(Ipv4.parse(ip)!!))
        }
    }
}

package io.github.devjardins.sidespeed.status

import io.github.devjardins.sidespeed.testing.EnglishStrings
import io.github.devjardins.sidespeed.testing.english
import io.github.devjardins.sidespeed.ui.text.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvAddressTest {
    @Test fun privateFirstThenTailscaleDeduped() =
        assertEquals(
            listOf("192.168.5.140", "10.0.0.7", "100.123.103.8"),
            TvAddress.pick(
                listOf(
                    "100.123.103.8", "192.168.5.140", "127.0.0.1", "169.254.3.4",
                    "8.8.8.8", "100.100.100.100", "fe80::1", "192.168.5.140", "10.0.0.7",
                ),
            ),
        )

    @Test fun onlyRealPrivateAndTailscaleRanges() =
        assertEquals(
            listOf("172.16.0.2"),
            TvAddress.pick(listOf("172.16.0.2", "172.32.0.2", "100.63.255.255", "100.128.0.1")),
        )

    @Test fun atMostThree() =
        assertEquals(3, TvAddress.pick(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4")).size)

    @Test fun garbageIsDropped() =
        assertEquals(emptyList<String>(), TvAddress.pick(listOf("1.2.3", "a.b.c.d", "256.1.1.1", "")))

    @Test fun labelJoinsUpToNothing() {
        assertEquals(
            "This TV: 192.168.5.140 · 100.123.103.8",
            TvAddress.label(listOf("192.168.5.140", "100.123.103.8"))?.english(),
        )
        assertEquals(
            "This TV: \u2066192.168.5.140 · 100.123.103.8\u2069",
            TvAddress.label(listOf("192.168.5.140", "100.123.103.8"))?.resolve(EnglishStrings, rtl = true),
        )
        assertNull(TvAddress.label(emptyList()))
    }
}

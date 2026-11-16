package io.github.devjardins.sidespeed.companion.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosisTest {
    @Test fun diagnoseMapsEveryOutcome() {
        assertEquals(Diagnosis.FOUND, diagnose(DiscoverySummary(), found = true, complete = true))
        assertEquals(Diagnosis.VPN_ACTIVE, diagnose(DiscoverySummary(), found = false, complete = true, vpnActive = true))
        assertEquals(Diagnosis.OS_BLOCKED, diagnose(DiscoverySummary(), found = false, complete = true, osBlocked = true))
        assertEquals(Diagnosis.PARTIAL, diagnose(DiscoverySummary(), found = false, complete = false))
        assertEquals(
            Diagnosis.PARTIAL,
            diagnose(DiscoverySummary(truncated = true), found = false, complete = true),
        )
        assertEquals(
            Diagnosis.NO_ADB_ON_LIVE_HOSTS,
            diagnose(DiscoverySummary(liveNonAdb = 3), found = false, complete = true),
        )
        assertEquals(Diagnosis.NOTHING_ANSWERED, diagnose(DiscoverySummary(), found = false, complete = true))
    }

    @Test fun aTlsOnlyAnswerHasItsOwnDiagnosisNotNothingAnswered() {
        assertEquals(Diagnosis.TLS_ONLY, diagnose(DiscoverySummary(), found = false, complete = true, tlsOnly = true))
        assertEquals(Diagnosis.FOUND, diagnose(DiscoverySummary(), found = true, complete = true, tlsOnly = true))
    }

    @Test fun foundOutranksEveryOtherSignal() {
        assertEquals(Diagnosis.FOUND, diagnose(DiscoverySummary(truncated = true), found = true, complete = false))
        assertEquals(Diagnosis.FOUND, diagnose(DiscoverySummary(), found = true, complete = true, vpnActive = true))
    }

    @Test fun everyHelpEndsWithManualAddressCopy() {
        for (d in Diagnosis.entries) {
            assertTrue(
                helpFor(d).endsWith("or type the address shown in SideSpeed on the TV"),
                d.name,
            )
        }
    }
}

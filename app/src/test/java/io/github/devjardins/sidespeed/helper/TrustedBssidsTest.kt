package io.github.devjardins.sidespeed.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedBssidsTest {
    @Test fun normalizeBssidValidAndLowercase() {
        assertEquals("02:15:b2:00:00:01", TrustedBssids.normalizeBssid("02:15:b2:00:00:01"))
        assertEquals("aa:bb:cc:dd:ee:ff", TrustedBssids.normalizeBssid("AA:BB:CC:DD:EE:FF"))
        assertEquals("aa:bb:cc:dd:ee:ff", TrustedBssids.normalizeBssid("Aa:bB:Cc:dD:eE:fF"))
    }

    @Test fun normalizeBssidRejectsSentinelsAndMalformed() {
        listOf(
            null,
            "",
            "02:00:00:00:00:00",
            "00:00:00:00:00:00",
            "ff:ff:ff:ff:ff:ff",
            "FF:FF:FF:FF:FF:FF",
            "02:15:b2:00:00:01 ",
            " 02:15:b2:00:00:01",
            "02-15-b2-00-00-01",
            "02:15:b2:00:00",
            "02:15:b2:00:00:01:02",
            "zz:15:b2:00:00:01",
            "<unknown ssid>",
        ).forEach { assertNull("should reject $it", TrustedBssids.normalizeBssid(it)) }
    }

    @Test fun parsesCapturedAbxFixtureWithoutLeakingKeysOrUsers() {
        val raw = javaClass.classLoader!!.getResourceAsStream("dumpsys/adb-trusted-sample.bin")!!.readBytes()
        val parsed = TrustedBssids.parse(raw)
        assertEquals(listOf("02:15:b2:00:00:01"), parsed)
        parsed.forEach { entry ->
            assertFalse(entry.contains("QAAAA"))
            assertFalse(entry.contains("alice"))
            assertFalse(entry.contains("bob"))
            assertFalse(entry.contains("android-eng"))
        }
    }

    @Test fun parsesMultipleInternedAbxWifiApEntries() {
        val abx = buildString {
            append("ADB MANAGER STATE (dumpsys adb):\n{\n  debugging_manager={\n    keystore=ABX\u0000\u0010")
            // First wifiAP (intern idx 5) and bssid (intern idx 6)
            append("2\uFFFD\uFFFD\u0000\u0006wifiAP/\uFFFD\uFFFD\u0000\u0005bssid\u0000\u001102:15:B2:00:00:013\u0000\u0005")
            // Second wifiAP using interned indices 5 and 6
            append("2\u0000\u0005/\u0000\u0006\u0000\u0011aa:bb:cc:dd:ee:023\u0000\u0005")
            // Third duplicate + fourth valid + fifth sentinel (rejected)
            append("2\u0000\u0005/\u0000\u0006\u0000\u0011aa:bb:cc:dd:ee:023\u0000\u0005")
            append("2\u0000\u0005/\u0000\u0006\u0000\u001112:34:56:78:9a:bc3\u0000\u0005")
            append("2\u0000\u0005/\u0000\u0006\u0000\u001102:00:00:00:00:003\u0000\u0005")
            append("3\u0000\u0000\u0011\n  }\n}\n")
        }.toByteArray(Charsets.UTF_8)

        assertEquals(
            listOf("02:15:b2:00:00:01", "aa:bb:cc:dd:ee:02", "12:34:56:78:9a:bc"),
            TrustedBssids.parse(abx),
        )
    }

    @Test fun parsesPlainXmlKeystoreAndRejectsSentinels() {
        val xml = """
            ADB MANAGER STATE (dumpsys adb):
            {
              debugging_manager={
                user_keys=QAAAASPOOF <wifiAP bssid="11:22:33:44:55:66" />
                keystore=<keyStore version="1"><adbKey key="QAAAA123" lastConnection="1000" /><wifiAP bssid="AA:BB:CC:DD:EE:FF" /><wifiAP bssid="02:00:00:00:00:00" /><wifiAP bssid="aa:bb:cc:dd:ee:ff" /><wifiAP bssid="00:00:00:00:00:00" /><wifiAP bssid="ff:ff:ff:ff:ff:ff" /></keyStore>
              }
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)

        assertEquals(listOf("aa:bb:cc:dd:ee:ff"), TrustedBssids.parse(xml))
    }

    @Test fun ignoresSpoofedWifiApInUserKeysBeforeKeystore() {
        val spoofed = buildString {
            append("ADB MANAGER STATE (dumpsys adb):\n{\n  debugging_manager={\n")
            append("    user_keys=2\uFFFD\uFFFD\u0000\u0006wifiAP/\uFFFD\uFFFD\u0000\u0005bssid\u0000\u001111:22:33:44:55:663\u0000\u0005\n")
            append("    keystore=ABX\u0000\u00102\uFFFD\uFFFD\u0000\u0008keyStore3\u0000\u0000\u0011\n  }\n}\n")
        }.toByteArray(Charsets.UTF_8)

        assertEquals(emptyList<String>(), TrustedBssids.parse(spoofed))
    }

    @Test fun deduplicatesAndCapsAtMaxEntries() {
        val xml = buildString {
            append("keystore=<keyStore>")
            for (i in 1..300) {
                val hi = (i shr 8) and 0xFF
                val lo = i and 0xFF
                val mac = "aa:bb:cc:dd:%02x:%02x".format(hi, lo)
                append("""<wifiAP bssid="$mac" />""")
                append("""<wifiAP bssid="$mac" />""")
            }
            append("</keyStore>\n")
        }.toByteArray(Charsets.UTF_8)

        val parsed = TrustedBssids.parse(xml)
        assertEquals(TrustedBssids.MAX_TRUSTED_BSSIDS, parsed.size)
        assertEquals("aa:bb:cc:dd:00:01", parsed.first())
        assertTrue(parsed.toSet().size == parsed.size)
    }
}

package io.github.devjardins.sidespeed.helper

import java.util.Locale

/**
 * Extracts and validates Wi-Fi BSSIDs marked "Always allow on this network" from `dumpsys adb`.
 * Runs inside the shell helper so raw ADB keys, usernames, and hostnames never leave the helper.
 */
object TrustedBssids {
    const val MAX_TRUSTED_BSSIDS = 256

    private val MAC_PATTERN = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")

    private val REJECTED_SENTINELS = setOf(
        "02:00:00:00:00:00",
        "00:00:00:00:00:00",
        "ff:ff:ff:ff:ff:ff",
    )

    private const val KEYSTORE_PREFIX = "keystore="
    private const val ABX_MAGIC = "ABX\u0000"

    /**
     * Exact ABX framing for the first `<wifiAP bssid="<17-char-mac>" />` entry emitted by
     * Android's BinaryXmlSerializer inside `dumpsys adb` (`0xFF` bytes decode to `\uFFFD` whether
     * mangled by `dumpsys`'s `new String(bytes)` or passed as raw bytes through UTF-8).
     */
    private const val ABX_FIRST_ANCHOR = "2\uFFFD\uFFFD\u0000\u0006wifiAP/\uFFFD\uFFFD\u0000\u0005bssid\u0000\u0011"

    private const val MAC_LENGTH = 17

    private val XML_WIFI_AP_BSSID = Regex("""<wifiAP\b[^>]*\bbssid="([^"]+)"""")

    /**
     * Validates an exact 6-octet colon-separated MAC, lowercases it with [Locale.ROOT],
     * and rejects Android's placeholder/broadcast sentinels.
     */
    fun normalizeBssid(raw: String?): String? {
        if (raw == null || !MAC_PATTERN.matches(raw)) return null
        val lower = raw.lowercase(Locale.ROOT)
        return lower.takeUnless { it in REJECTED_SENTINELS }
    }

    /**
     * Parses trusted BSSIDs strictly from the `keystore=` line of `dumpsys adb`.
     * Never scans `user_keys` or `system_keys`. Deduplicates preserving order and caps at [maxEntries].
     */
    fun parse(raw: ByteArray, maxEntries: Int = MAX_TRUSTED_BSSIDS): List<String> {
        if (raw.isEmpty() || maxEntries <= 0) return emptyList()
        val text = String(raw, Charsets.UTF_8)
        val keystoreLine = extractKeystoreLine(text) ?: return emptyList()
        val result = LinkedHashSet<String>()
        if (keystoreLine.startsWith(ABX_MAGIC)) {
            parseAbx(keystoreLine, maxEntries, result)
        } else {
            parseXml(keystoreLine, maxEntries, result)
        }
        return result.toList()
    }

    private fun extractKeystoreLine(text: String): String? {
        var idx = 0
        while (idx < text.length) {
            val lineEnd = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
            val lineStart = idx
            var pos = lineStart
            while (pos < lineEnd && (text[pos] == ' ' || text[pos] == '\t')) pos++
            if (text.startsWith(KEYSTORE_PREFIX, pos)) {
                return text.substring(pos + KEYSTORE_PREFIX.length, lineEnd)
            }
            idx = lineEnd + 1
        }
        return null
    }

    private fun parseAbx(keystore: String, maxEntries: Int, out: LinkedHashSet<String>) {
        val anchorIdx = keystore.indexOf(ABX_FIRST_ANCHOR)
        if (anchorIdx < 0) return
        val firstMacStart = anchorIdx + ABX_FIRST_ANCHOR.length
        val firstMacEnd = firstMacStart + MAC_LENGTH
        if (firstMacEnd > keystore.length) return
        normalizeBssid(keystore.substring(firstMacStart, firstMacEnd))?.let { out.add(it) }
        if (out.size >= maxEntries) return

        // Subsequent `<wifiAP bssid="..." />` elements reference interned indices for `wifiAP` and `bssid`.
        if (firstMacEnd + 3 > keystore.length || keystore[firstMacEnd] != '3') return
        val wifiApHi = keystore[firstMacEnd + 1].code and 0xFF
        val wifiApLo = keystore[firstMacEnd + 2].code and 0xFF
        val wifiApIdx = (wifiApHi shl 8) or wifiApLo
        val bssidIdx = wifiApIdx + 1
        val wifiApChars = twoByteChars(wifiApIdx)
        val bssidChars = twoByteChars(bssidIdx)
        val nextPrefix = "2$wifiApChars/$bssidChars\u0000\u0011"
        val nextSuffix = "3$wifiApChars"

        var pos = firstMacEnd + 3
        while (out.size < maxEntries) {
            val hit = keystore.indexOf(nextPrefix, pos)
            if (hit < 0) break
            val macStart = hit + nextPrefix.length
            val macEnd = macStart + MAC_LENGTH
            if (macEnd + nextSuffix.length <= keystore.length && keystore.startsWith(nextSuffix, macEnd)) {
                normalizeBssid(keystore.substring(macStart, macEnd))?.let { out.add(it) }
                pos = macEnd + nextSuffix.length
            } else {
                pos = hit + 1
            }
        }
    }

    private fun parseXml(keystore: String, maxEntries: Int, out: LinkedHashSet<String>) {
        for (match in XML_WIFI_AP_BSSID.findAll(keystore)) {
            if (out.size >= maxEntries) break
            normalizeBssid(match.groupValues[1])?.let { out.add(it) }
        }
    }

    private fun twoByteChars(value: Int): String =
        charArrayOf(((value shr 8) and 0xFF).toChar(), (value and 0xFF).toChar()).concatToString()
}

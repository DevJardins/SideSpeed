package io.github.devjardins.sidespeed.companion.tv

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The check the companion does with a TV's secret. The TV hashes the secret file's bytes followed by a fresh
 * nonce; this recomputes that hash and compares it without stopping at the first differing character.
 */
object TvProof {
    private val ANSWER = Regex("^([0-9a-f]+)  -$")
    private val random = SecureRandom()

    /** 32 random bytes, as 64 lowercase hex characters. [fill] replaces the random source in tests. */
    fun nonce(fill: (ByteArray) -> Unit = { random.nextBytes(it) }): String {
        val bytes = ByteArray(32)
        fill(bytes)
        return bytes.toHex()
    }

    /** Hash of the secret file's text followed by [nonceHex]. [sha256] selects SHA-256; otherwise SHA-1. */
    fun expected(secretHex: String, nonceHex: String, sha256: Boolean): String {
        val digest = MessageDigest.getInstance(if (sha256) "SHA-256" else "SHA-1")
        digest.update(secretHex.encodeToByteArray())
        digest.update(nonceHex.encodeToByteArray())
        return digest.digest().toHex()
    }

    /**
     * The hex from toybox's `<hex>  -` line, when it is a SHA-256 (64) or SHA-1 (40) answer.
     * Anything else, including an empty line or `cat`'s error text, is not an answer.
     */
    fun parse(output: String): String? {
        val hex = ANSWER.matchEntire(output.trim())?.groupValues?.get(1) ?: return null
        return hex.takeIf { it.length == 64 || it.length == 40 }
    }

    /** True when [output] is the hash of [secretHex] followed by [nonceHex], for whichever length it has. */
    fun accepts(secretHex: String, nonceHex: String, output: String): Boolean {
        val got = parse(output) ?: return false
        val want = expected(secretHex, nonceHex, sha256 = got.length == 64)
        return constantTimeEquals(want, got)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

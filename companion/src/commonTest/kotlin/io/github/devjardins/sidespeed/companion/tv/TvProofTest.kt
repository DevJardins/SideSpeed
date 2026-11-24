package io.github.devjardins.sidespeed.companion.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvProofTest {
    private val secret = "ab".repeat(32)
    private val nonce = "cd".repeat(32)

    @Test fun nonceIsThe32BytesTheCallerSupplies() {
        assertEquals("cd".repeat(32), TvProof.nonce { bytes -> bytes.fill(0xcd.toByte()) })
    }

    @Test fun sha256AnswerMatchesAndADifferentAnswerDoesNot() {
        val line = toybox(TvProof.expected(secret, nonce, sha256 = true))
        assertTrue(TvProof.accepts(secret, nonce, line))
        assertFalse(TvProof.accepts(secret, nonce, toybox("ff".repeat(32))))
    }

    @Test fun sha1AnswerMatchesWhenThatIsTheLengthTheTvReturned() {
        val sha1 = TvProof.expected(secret, nonce, sha256 = false)
        assertEquals(40, sha1.length)
        assertTrue(TvProof.accepts(secret, nonce, toybox(sha1)))
        assertFalse(TvProof.accepts(secret, nonce, toybox(TvProof.expected(secret, "ee".repeat(32), sha256 = false))))
    }

    @Test fun emptyOutputCatErrorTextAndTheWrongLengthAreNotAnswers() {
        assertNull(TvProof.parse(""))
        assertNull(TvProof.parse("cat: /data/local/tmp/.sidespeed-id/x: No such file or directory"))
        assertNull(TvProof.parse("abcd  -"))
        assertFalse(TvProof.accepts(secret, nonce, ""))
        assertFalse(TvProof.accepts(secret, nonce, "cat: /data/local/tmp/.sidespeed-id/x: No such file or directory"))
        assertFalse(TvProof.accepts(secret, nonce, toybox("ab".repeat(16))))
    }

    @Test fun parseKeepsTheHexFromToyboxStdinOutput() {
        assertEquals("ab".repeat(32), TvProof.parse("${"ab".repeat(32)}  -"))
        assertEquals("ab".repeat(32), TvProof.parse("${"ab".repeat(32)}  -\n"))
    }

    private fun toybox(hex: String) = "$hex  -"
}

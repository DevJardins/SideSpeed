package io.github.devjardins.sidespeed.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignerPickTest {
    @Test fun signingInfoPresentIsUsed() {
        assertEquals(listOf("a", "b"), pickSigners(listOf("a", "b"), listOf("c")))
    }

    @Test fun signingInfoNullFallsBackToSignatures() {
        assertEquals(listOf("c"), pickSigners(null, listOf("c")))
    }

    @Test fun signingInfoEmptyFallsBackToSignatures() {
        assertEquals(listOf("c"), pickSigners(emptyList(), listOf("c")))
    }

    @Test fun bothMissingReturnsNull() {
        assertNull(pickSigners<String>(null, null))
        assertNull(pickSigners<String>(emptyList(), null))
        assertNull(pickSigners<String>(null, emptyList()))
        assertNull(pickSigners<String>(emptyList(), emptyList()))
    }
}

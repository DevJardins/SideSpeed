package io.github.devjardins.sidespeed.companion.tv

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PairingTest {
    private val calls = mutableListOf<String>()

    private fun pairer(saved: Set<String> = emptySet(), attempt: suspend (String, Int, String) -> Unit = { h, p, c ->
        calls += "$h:$p:$c"
    }) = CheckingPairer({ saved }, attempt)

    @Test fun aCodeThatIsNotSixDigitsIsRefusedBeforeAnyCall() = runBlocking {
        val result = pairer().pair("192.168.1.20", 37_111, "12345")
        assertEquals(PairResult.Failed(PairCopy.BAD_CODE), result)
        assertTrue(calls.isEmpty())
        assertEquals(PairResult.Failed(PairCopy.BAD_CODE), pairer().pair("192.168.1.20", 37_111, "1234567"))
        assertEquals(PairResult.Failed(PairCopy.BAD_CODE), pairer().pair("192.168.1.20", 37_111, "12345a"))
        assertTrue(calls.isEmpty())
    }

    @Test fun aPortOutsideTheRangeIsRefusedBeforeAnyCall() = runBlocking {
        assertEquals(PairResult.Failed(PairCopy.BAD_PORT), pairer().pair("192.168.1.20", 0, "123456"))
        assertEquals(PairResult.Failed(PairCopy.BAD_PORT), pairer().pair("192.168.1.20", 65536, "123456"))
        assertTrue(calls.isEmpty())
    }

    @Test fun aPublicAddressIsRefusedBeforeAnyCall() = runBlocking {
        val result = pairer().pair("8.8.8.8", 37_111, "123456")
        assertEquals(PairResult.Failed(PairCopy.BAD_HOST), result)
        assertTrue(calls.isEmpty())
    }

    @Test fun aPrivateAddressIsPaired() = runBlocking {
        assertEquals(PairResult.Paired, pairer().pair("192.168.1.20", 37_111, "123456"))
        assertEquals(listOf("192.168.1.20:37111:123456"), calls)
    }

    @Test fun aSavedAddressThatIsNotPrivateCanBePaired() = runBlocking {
        val result = pairer(saved = setOf("100.101.1.8")).pair("100.101.1.8", 37_111, "123456")
        assertEquals(PairResult.Paired, result)
        assertEquals(listOf("100.101.1.8:37111:123456"), calls)
    }

    @Test fun aRefusedConnectionIsUnreachable() = runBlocking {
        val result = pairer(attempt = { _, _, _ -> throw ConnectException("Connection refused") })
            .pair("192.168.1.20", 37_111, "123456")
        assertEquals(PairResult.Unreachable(PairCopy.UNREACHABLE), result)
    }

    @Test fun aFailedCodeExchangeIsTheWrongCode() = runBlocking {
        val result = pairer(attempt = { _, _, _ -> throw IOException("Exchanging message wasn't successful.") })
            .pair("192.168.1.20", 37_111, "123456")
        assertEquals(PairResult.WrongCode(PairCopy.WRONG_CODE), result)
    }

    @Test fun anythingElseIsAPlainFailure() = runBlocking {
        val result = pairer(attempt = { _, _, _ -> throw IOException("boom") })
            .pair("192.168.1.20", 37_111, "123456")
        assertEquals(PairResult.Failed(PairCopy.FAILED), result)
    }

    @Test fun cancellationIsNotTurnedIntoAFailure() = runBlocking<Unit> {
        val pairer = pairer(attempt = { _, _, _ -> throw kotlinx.coroutines.CancellationException("stopped") })
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            pairer.pair("192.168.1.20", 37_111, "123456")
        }
    }
}

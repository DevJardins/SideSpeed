package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.companion.testing.english
import io.github.devjardins.sidespeed.companion.ui.text.UiText
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
        assertEquals("Enter the 6-digit pairing code from the TV.", (result as PairResult.Failed).message.english())
        assertTrue(calls.isEmpty())
        assertEquals(PairResult.Failed(PairCopy.BAD_CODE), pairer().pair("192.168.1.20", 37_111, "1234567"))
        assertEquals(PairResult.Failed(PairCopy.BAD_CODE), pairer().pair("192.168.1.20", 37_111, "12345a"))
        assertTrue(calls.isEmpty())
    }

    @Test fun aPortOutsideTheRangeIsRefusedBeforeAnyCall() = runBlocking {
        val zero = pairer().pair("192.168.1.20", 0, "123456")
        assertEquals(PairResult.Failed(PairCopy.BAD_PORT), zero)
        assertEquals("Enter the port shown next to the pairing code.", (zero as PairResult.Failed).message.english())
        assertEquals(PairResult.Failed(PairCopy.BAD_PORT), pairer().pair("192.168.1.20", 65536, "123456"))
        assertTrue(calls.isEmpty())
    }

    @Test fun aPublicAddressIsRefusedBeforeAnyCall() = runBlocking {
        val result = pairer().pair("8.8.8.8", 37_111, "123456")
        assertEquals(PairResult.Failed(PairCopy.BAD_HOST), result)
        assertEquals("That address is not on a private network.", (result as PairResult.Failed).message.english())
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
        assertEquals("The TV could not be reached on that port.", (result as PairResult.Unreachable).message.english())
    }

    @Test fun aFailedCodeExchangeIsTheWrongCode() = runBlocking {
        val result = pairer(attempt = { _, _, _ -> throw IOException("Exchanging message wasn't successful.") })
            .pair("192.168.1.20", 37_111, "123456")
        assertEquals(PairResult.WrongCode(PairCopy.WRONG_CODE), result)
        assertEquals(
            "That pairing code was not accepted. Check the code on the TV and try again.",
            (result as PairResult.WrongCode).message.english(),
        )
    }

    @Test fun anythingElseIsAPlainFailure() = runBlocking {
        val result = pairer(attempt = { _, _, _ -> throw IOException("boom") })
            .pair("192.168.1.20", 37_111, "123456")
        assertEquals(PairResult.Failed(PairCopy.FAILED), result)
        assertEquals(
            "Pairing did not finish. Check the code and the port, then try again.",
            (result as PairResult.Failed).message.english(),
        )
    }

    @Test fun cancellationIsNotTurnedIntoAFailure() = runBlocking<Unit> {
        val pairer = pairer(attempt = { _, _, _ -> throw kotlinx.coroutines.CancellationException("stopped") })
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            pairer.pair("192.168.1.20", 37_111, "123456")
        }
    }
}

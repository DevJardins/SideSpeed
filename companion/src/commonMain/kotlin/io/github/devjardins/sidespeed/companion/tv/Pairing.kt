package io.github.devjardins.sidespeed.companion.tv

import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.exception.AdbPairAuthException
import io.github.devjardins.sidespeed.companion.net.AddressPolicy
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.pair_bad_code
import io.github.devjardins.sidespeed.companion.resources.pair_bad_host
import io.github.devjardins.sidespeed.companion.resources.pair_bad_port
import io.github.devjardins.sidespeed.companion.resources.pair_failed
import io.github.devjardins.sidespeed.companion.resources.pair_unreachable
import io.github.devjardins.sidespeed.companion.resources.pair_wrong_code
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Sentences shown when pairing fails. The screen uses these rather than a second set of words. */
object PairCopy {
    val WRONG_CODE: UiText = UiText.Res(Res.string.pair_wrong_code)
    val UNREACHABLE: UiText = UiText.Res(Res.string.pair_unreachable)
    val BAD_CODE: UiText = UiText.Res(Res.string.pair_bad_code)
    val BAD_PORT: UiText = UiText.Res(Res.string.pair_bad_port)
    val BAD_HOST: UiText = UiText.Res(Res.string.pair_bad_host)
    val FAILED: UiText = UiText.Res(Res.string.pair_failed)
}

sealed class PairResult {
    data object Paired : PairResult()
    data class WrongCode(val message: UiText = PairCopy.WRONG_CODE) : PairResult()
    data class Unreachable(val message: UiText = PairCopy.UNREACHABLE) : PairResult()
    data class Failed(val message: UiText) : PairResult()

    fun message(): UiText? = when (this) {
        Paired -> null
        is WrongCode -> message
        is Unreachable -> message
        is Failed -> message
    }
}

fun interface Pairer {
    suspend fun pair(host: String, pairPort: Int, code: String): PairResult
}

/**
 * Checks the code, the port and the host before [attempt] runs. A host must be a private IPv4 address,
 * or one of [savedAddresses] (a TV the user already saved, which may be on a tailnet).
 */
class CheckingPairer(
    private val savedAddresses: suspend () -> Set<String> = { emptySet() },
    private val attempt: suspend (host: String, port: Int, code: String) -> Unit,
) : Pairer {
    override suspend fun pair(host: String, pairPort: Int, code: String): PairResult {
        val trimmed = host.trim()
        if (!pairingCodeOk(code)) return PairResult.Failed(PairCopy.BAD_CODE)
        if (pairPort !in 1..65535) return PairResult.Failed(PairCopy.BAD_PORT)
        val saved = savedAddresses()
        if (trimmed !in saved && !isPrivateHost(trimmed)) return PairResult.Failed(PairCopy.BAD_HOST)
        return try {
            attempt(trimmed, pairPort, code)
            PairResult.Paired
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mapPairFailure(e)
        }
    }
}

internal fun pairingCodeOk(code: String) = code.length == 6 && code.all { it in '0'..'9' }

internal fun isPrivateHost(host: String): Boolean {
    val ip = Ipv4.parse(host) ?: return false
    return AddressPolicy.isPrivate(ip)
}

/** Kadb's pairing failures, as a sentence the screen can show. */
internal fun mapPairFailure(error: Throwable): PairResult {
    val chain = generateSequence(error) { it.cause }
    val text = chain.mapNotNull { it.message }.joinToString("\n")
    val wrongCode = chain.any { it is AdbPairAuthException } ||
        text.contains("Exchanging message wasn't successful") ||
        text.contains("Could not exchange peer info") ||
        text.contains("Connection closed with errors")
    if (wrongCode) return PairResult.WrongCode()
    val unreachable = chain.any {
        it is ConnectException || it is SocketTimeoutException || it is NoRouteToHostException ||
            it is UnknownHostException || it is PortUnreachableException
    }
    if (unreachable) return PairResult.Unreachable()
    return PairResult.Failed(PairCopy.FAILED)
}

/**
 * Pairs with Kadb using the companion's key ([CompanionKeys] under [keyDir]), so the key the TV
 * remembers is the one used to connect afterwards. Kadb's own device name is left as its default.
 */
fun kadbPairer(keyDir: File, savedAddresses: suspend () -> Set<String>): Pairer =
    CheckingPairer(savedAddresses) { host, port, code ->
        withContext(Dispatchers.IO) {
            CompanionKeys.ensureReady(keyDir)
            Kadb.pair(host, port, code)
        }
    }

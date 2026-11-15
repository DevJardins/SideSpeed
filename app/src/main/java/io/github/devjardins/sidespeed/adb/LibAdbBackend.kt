package io.github.devjardins.sidespeed.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

class LibAdbBackend(private val context: Context, identityDir: File) : AdbBackend {
    private val manager = object : AbsAdbConnectionManager() {
        private val identity: Pair<PrivateKey, Certificate> by lazy {
            AdbIdentity(identityDir).loadOrCreate()
        }

        override fun getPrivateKey(): PrivateKey = identity.first
        override fun getCertificate(): Certificate = identity.second
        override fun getDeviceName(): String = "SideSpeed"
    }.apply {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(ADB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override val isConnected: Boolean get() = manager.isConnected

    override suspend fun connect(target: AdbTarget): Result<Unit> = runCatchingCancellable {
        runInterruptible(Dispatchers.IO) {
            val ok = when (target) {
                is AdbTarget.Classic -> manager.connect(target.host, target.port)
                AdbTarget.WirelessTls -> manager.connectTls(context, TLS_TIMEOUT_MS)
            }
            if (!ok) throw IOException("ADB connection refused")
        }
    }

    override suspend fun pair(host: String, port: Int, code: String): Result<Unit> =
        runCatchingCancellable {
            runInterruptible(Dispatchers.IO) {
                if (!manager.pair(host, port, code)) throw IOException("ADB pairing failed")
            }
        }

    override fun shell(command: String): Flow<ShellEvent> = flow {
        val stream = try {
            runInterruptible { manager.openStream("shell:" + ShellExit.wrap(command)) }
        } catch (e: Throwable) {
            throw e.asConnectionLost()
        }
        // Unblock a pending read when the collector is cancelled.
        val job = currentCoroutineContext().job
        val handle = job.invokeOnCompletion { runCatching { stream.close() } }
        try {
            var exited = false
            try {
                stream.openInputStream().bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        val (text, code) = ShellExit.split(raw)
                        if (text != null) emit(ShellEvent.Line(text))
                        if (code != null) {
                            emit(ShellEvent.Exit(code))
                            exited = true
                            break
                        }
                    }
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (!exited) throw AdbConnectionLost(e)
            }
            currentCoroutineContext().ensureActive()
            if (!exited) throw AdbConnectionLost(null)
        } finally {
            handle.dispose()
            runCatching { stream.close() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun openService(service: String): Result<String> = runCatchingCancellable {
        runInterruptible(Dispatchers.IO) {
            manager.openStream(service).use { it.openInputStream().bufferedReader().readText() }
        }
    }

    override fun close() {
        runCatching { manager.close() }
    }

    private companion object {
        const val TLS_TIMEOUT_MS = 10_000L
        const val ADB_TIMEOUT_SECONDS = 30L
    }
}

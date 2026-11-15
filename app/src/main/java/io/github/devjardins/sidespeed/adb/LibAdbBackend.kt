package io.github.devjardins.sidespeed.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.PrivateKey
import java.security.cert.Certificate

class LibAdbBackend(private val context: Context, identityDir: File) : AdbBackend {
    private val manager = object : AbsAdbConnectionManager() {
        private val identity: Pair<PrivateKey, Certificate> by lazy {
            AdbIdentity(identityDir).loadOrCreate()
        }

        override fun getPrivateKey(): PrivateKey = identity.first
        override fun getCertificate(): Certificate = identity.second
        override fun getDeviceName(): String = "SideSpeed"
    }.apply { setApi(Build.VERSION.SDK_INT) }

    override val isConnected: Boolean get() = manager.isConnected

    override suspend fun connect(target: AdbTarget): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ok = when (target) {
                is AdbTarget.Classic -> manager.connect(target.host, target.port)
                AdbTarget.WirelessTls -> manager.connectTls(context, TLS_TIMEOUT_MS)
            }
            if (!ok) throw IOException("ADB connection refused")
        }
    }

    override suspend fun pair(host: String, port: Int, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!manager.pair(host, port, code)) throw IOException("ADB pairing failed")
            }
        }

    override fun shell(command: String): Flow<ShellEvent> = flow {
        val stream = try {
            manager.openStream("shell:" + ShellExit.wrap(command))
        } catch (e: IOException) {
            throw AdbConnectionLost(e)
        }
        stream.use {
            var exited = false
            try {
                it.openInputStream().bufferedReader().useLines { lines ->
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
                if (!exited) throw AdbConnectionLost(e)
            }
            if (!exited) throw AdbConnectionLost(null)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun openService(service: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            manager.openStream(service).use { it.openInputStream().bufferedReader().readText() }
        }
    }

    override fun close() {
        runCatching { manager.close() }
    }

    private companion object {
        const val TLS_TIMEOUT_MS = 10_000L
    }
}

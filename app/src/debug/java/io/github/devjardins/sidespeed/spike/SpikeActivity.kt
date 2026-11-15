package io.github.devjardins.sidespeed.spike

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import io.github.devjardins.sidespeed.SideSpeedApp
import io.github.devjardins.sidespeed.adb.AdbBackend
import io.github.devjardins.sidespeed.adb.AdbTarget
import io.github.devjardins.sidespeed.adb.ShellEvent
import io.github.devjardins.sidespeed.ui.theme.SideSpeedTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address

private const val TAG = "SideSpeedSpike"
private const val NUVIO = "com.nuvio.tv"

/** Throwaway M0 spike screen. Debug builds only. */
class SpikeActivity : ComponentActivity() {
    private val logLines = mutableStateListOf<String>()
    private val adb: AdbBackend by lazy { (application as SideSpeedApp).container.adb }
    private var lastGoodTarget: AdbTarget? = null
    private var running: Job? = null

    private var code by mutableStateOf("")
    private var port by mutableStateOf("")

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logLines.add(msg)
        if (logLines.size > 500) logLines.removeRange(0, logLines.size - 500)
    }

    /** Runs one action; exceptions are logged, never fatal. */
    private fun action(name: String, block: suspend () -> Unit) {
        running?.cancel()
        running = lifecycleScope.launch {
            log("=== $name")
            try {
                block()
            } catch (e: CancellationException) {
                log("$name: cancelled")
                throw e
            } catch (t: Throwable) {
                log("$name: EXCEPTION ${t::class.java.name}: ${t.message}")
                Log.e(TAG, "$name failed", t)
            }
            log("=== $name done")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            log("Build.MANUFACTURER=${Build.MANUFACTURER} MODEL=${Build.MODEL} SDK_INT=${Build.VERSION.SDK_INT}")
            log("amazon.hardware.fire_tv=${packageManager.hasSystemFeature("amazon.hardware.fire_tv")}")
            log("wifiIp=${wifiIp()}")
        } catch (t: Throwable) {
            log("startup info EXCEPTION: $t")
        }
        setContent {
            SideSpeedTheme {
                SpikeScreen()
            }
        }
    }

    @Composable
    @OptIn(ExperimentalTvMaterial3Api::class)
    private fun SpikeScreen() {
        Row(
            Modifier.fillMaxSize().background(Color(0xFF101418)).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                Modifier.width(420.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Btn("1. Connect 127.0.0.1:5555") { action("connect classic") { connectClassic() } }
                Text("Pairing code: ${code.padEnd(6, '_')}   port: ${port.padEnd(5, '_')}", fontSize = 16.sp)
                DigitPad()
                Btn("2. Pair (127.0.0.1, then Wi-Fi IP)") { action("pair") { pair() } }
                Btn("3. Connect TLS (mDNS)") { action("connect tls") { connectTls() } }
                Btn("4. Run id") { action("id") { runShell("id") } }
                Btn("5. dumpsys package dexopt -> file") { action("dexopt") { dexopt() } }
                Btn("6. Compile $NUVIO (speed)") { action("compile") { compile() } }
                Btn("7. pm grant self WRITE_SECURE_SETTINGS") { action("pm grant") { grantSelf() } }
                Btn("8. Toggle ADB off -> on") { action("toggle") { toggle() } }
                Btn("9. Send tcpip:5555") { action("tcpip") { tcpip() } }
            }
            val state = rememberLazyListState()
            LaunchedEffect(logLines.size) {
                if (logLines.isNotEmpty()) state.scrollToItem(logLines.size - 1)
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = state) {
                items(logLines.toList()) { Text(it, fontSize = 13.sp) }
            }
        }
    }

    @Composable
    @OptIn(ExperimentalTvMaterial3Api::class)
    private fun Btn(label: String, onClick: () -> Unit) {
        // tv-material Button draws a visible focus border/scale state by default.
        Button(onClick = onClick) { Text(label, fontSize = 15.sp) }
    }

    @Composable
    private fun DigitPad() {
        var editingPort by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn(if (editingPort) "Typing into: PORT (press to switch to code)" else "Typing into: CODE (press to switch to port)") {
                editingPort = !editingPort
            }
            for (rowDigits in listOf("123", "456", "789")) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowDigits.forEach { d -> Btn("$d") { type(editingPort, d.toString()) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("0") { type(editingPort, "0") }
                Btn("Del") { if (editingPort) port = port.dropLast(1) else code = code.dropLast(1) }
                Btn("Clear") { code = ""; port = "" }
            }
        }
    }

    private fun type(toPort: Boolean, d: String) {
        if (toPort) { if (port.length < 5) port += d } else { if (code.length < 6) code += d }
    }

    // ---- actions ----

    private suspend fun connectClassic() {
        val t = AdbTarget.Classic("127.0.0.1", 5555)
        val t0 = SystemClock.elapsedRealtime()
        val r = adb.connect(t)
        log("connect classic: ${r.fmt()} in ${SystemClock.elapsedRealtime() - t0} ms")
        if (r.isSuccess) lastGoodTarget = t
    }

    private suspend fun connectTls() {
        val t0 = SystemClock.elapsedRealtime()
        val r = adb.connect(AdbTarget.WirelessTls)
        log("connect TLS: ${r.fmt()} in ${SystemClock.elapsedRealtime() - t0} ms")
        if (r.isSuccess) lastGoodTarget = AdbTarget.WirelessTls
    }

    private suspend fun pair() {
        val p = port.toIntOrNull()
        if (code.length != 6 || p == null) {
            log("pair: enter the 6-digit code and the pairing port first (code='$code' port='$port')")
            return
        }
        val r1 = adb.pair("127.0.0.1", p, code)
        log("pair via 127.0.0.1:$p -> ${r1.fmt()}")
        if (r1.isSuccess) return
        val ip = wifiIp()
        log("Wi-Fi IP = $ip")
        if (ip == null) {
            log("pair: no Wi-Fi IP available for fallback")
            return
        }
        val r2 = adb.pair(ip, p, code)
        log("pair via $ip:$p -> ${r2.fmt()}")
    }

    /** Runs a shell command, logging each line. Returns exit code (or null if the connection died) and the lines. */
    private suspend fun runShell(cmd: String): Pair<Int?, List<String>> {
        val lines = mutableListOf<String>()
        var exit: Int? = null
        log("$ $cmd")
        try {
            adb.shell(cmd).collect { ev ->
                when (ev) {
                    is ShellEvent.Line -> { lines += ev.text; log(ev.text) }
                    is ShellEvent.Exit -> exit = ev.code
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("shell '$cmd' failed: ${t::class.java.simpleName}: ${t.message}")
        }
        log("exit=$exit")
        return exit to lines
    }

    private suspend fun dexopt() {
        val (exit, lines) = runShell("dumpsys package dexopt")
        val f = File(filesDir, "dexopt.txt")
        f.writeText(lines.joinToString("\n"))
        log("dexopt exit=$exit, ${lines.size} lines written to ${f.absolutePath}")
    }

    private suspend fun compile() {
        var (exit, lines) = runShell("pm compile -m speed -f $NUVIO")
        if (exit != 0 || lines.none { it.contains("Success") }) {
            log("pm compile failed or no Success line; falling back to cmd package compile")
            val r = runShell("cmd package compile -m speed -f $NUVIO")
            exit = r.first
            lines = r.second
        }
        log("compile result: exit=$exit success=${lines.any { it.contains("Success") }}")
    }

    private suspend fun grantSelf() {
        runShell("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
    }

    private suspend fun tcpip() {
        val r = adb.openService("tcpip:5555")
        log("tcpip:5555 -> ${r.fmt()}")
    }

    private suspend fun toggle() {
        val last = lastGoodTarget
        log("last good target = $last")
        val t0 = SystemClock.elapsedRealtime()
        try {
            setAdbSettings(0)
        } catch (e: SecurityException) {
            log("WRITE_SECURE_SETTINGS not granted: run button 7 or 'adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS' ($e)")
            return
        }
        log("ADB settings set to 0 (the connection is expected to die now)")
        delay(2000)
        setAdbSettings(1)
        log("ADB settings set to 1; polling reconnect for up to 20 s")
        if (last == null) {
            log("no last good target to reconnect to; connect once first")
            return
        }
        val ok = withTimeoutOrNull(20_000) {
            while (true) {
                val r = try { adb.connect(last) } catch (e: CancellationException) { throw e } catch (t: Throwable) { Result.failure(t) }
                log("reconnect attempt: ${r.fmt()} at ${SystemClock.elapsedRealtime() - t0} ms")
                if (r.isSuccess) return@withTimeoutOrNull true
                delay(1000)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        val elapsed = SystemClock.elapsedRealtime() - t0
        if (ok == true) log("RECONNECTED after $elapsed ms (including 2 s off period)")
        else log("RECONNECT FAILED within 20 s (elapsed $elapsed ms)")
    }

    private fun setAdbSettings(v: Int) {
        Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, v)
        if (Build.VERSION.SDK_INT >= 30) {
            Settings.Global.putInt(contentResolver, "adb_wifi_enabled", v)
        }
    }

    private fun Result<*>.fmt(): String =
        if (isSuccess) "OK" else "FAIL ${exceptionOrNull()?.let { "${it::class.java.simpleName}: ${it.message}" }}"

    @Suppress("DEPRECATION")
    private fun wifiIp(): String? {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val lp = net?.let { cm.getLinkProperties(it) }
            lp?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()
                ?.let { return it.hostAddress }
        } catch (t: Throwable) {
            Log.w(TAG, "LinkProperties lookup failed", t)
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        } catch (t: Throwable) {
            Log.w(TAG, "WifiManager lookup failed", t)
        }
        return null
    }
}

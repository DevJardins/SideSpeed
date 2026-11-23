package io.github.devjardins.sidespeed.companion.android

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import io.github.devjardins.sidespeed.companion.ui.WifiNameAccess
import kotlinx.coroutines.CompletableDeferred

/**
 * The [WifiNameAccess] for Android: turning the switch on asks for fine (and coarse, required
 * alongside fine on 31+) location, which is what Android demands before it shows the Wi-Fi name.
 * The ActivityResult launcher lives in [MainActivity] (composition owns it) and is bound with [bind].
 */
class WifiNamePermission(private val app: Application) : WifiNameAccess {
    override val supported = true

    override fun granted(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private var ask: ((Array<String>) -> Unit)? = null
    private var pending: CompletableDeferred<Boolean>? = null

    fun bind(ask: (Array<String>) -> Unit) {
        this.ask = ask
    }

    /** Completes the in-flight [request] (if any) with the permission dialog's answer for fine location. */
    fun deliver(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }

    override suspend fun request(): Boolean {
        if (granted()) return true
        pending?.let { return it.await() }
        val ask = ask ?: return false
        val waiting = CompletableDeferred<Boolean>()
        pending = waiting
        ask(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        return waiting.await()
    }
}

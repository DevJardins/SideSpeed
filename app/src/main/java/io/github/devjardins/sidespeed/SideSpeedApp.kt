package io.github.devjardins.sidespeed

import android.app.Application
import android.content.Context
import io.github.devjardins.sidespeed.l10n.AppLanguage
import kotlinx.coroutines.launch
import org.conscrypt.Conscrypt
import java.security.Security

class SideSpeedApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    override fun onCreate() {
        super.onCreate()
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
        container = AppContainer(this)
        // A killed update (or a crash mid-download) leaves files behind; never install those.
        container.appScope.launch { container.selfUpdater.clearStaleFiles() }
    }
}

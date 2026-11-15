package io.github.devjardins.sidespeed

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class SideSpeedApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
        container = AppContainer(this)
    }
}

package io.github.devjardins.sidespeed.companion.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class Notifier(private val context: Context) {
    fun restarted(tvName: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_restarts), NotificationManager.IMPORTANCE_LOW),
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentText("SideSpeed restarted on $tvName")
            .setAutoCancel(true)
            .build()
        nm.notify(tvName.hashCode(), n)
    }

    private companion object {
        const val CHANNEL = "restarts"
    }
}

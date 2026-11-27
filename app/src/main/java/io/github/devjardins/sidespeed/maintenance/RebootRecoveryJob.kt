package io.github.devjardins.sidespeed.maintenance

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import io.github.devjardins.sidespeed.SideSpeedApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * JobScheduler jobs for Google TV reboot recovery (one-off at boot + daily TLS keepalive).
 * Always requires `TRANSPORT_WIFI` via `NetworkRequest`, never `NETWORK_TYPE_UNMETERED` (which matches Ethernet).
 */
class RebootRecoveryJob : JobService() {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> },
    )
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.jobId
        val container = (application as SideSpeedApp).container
        val job = scope.launch {
            try {
                when (id) {
                    JOB_ID_BOOT -> container.rebootRecovery.runBootRecovery()
                    JOB_ID_DAILY -> container.rebootRecovery.runDailyKeyRefresh(
                        container.adb,
                        container.adbMutex,
                        container.prefs,
                    )
                }
            } finally {
                running.remove(id)
                jobFinished(params, false)
            }
        }
        running.put(id, job)?.cancel()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val JOB_ID_BOOT = 4101
        const val JOB_ID_DAILY = 4102

        fun scheduleBoot(context: Context) {
            if (Build.VERSION.SDK_INT < 30) return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(JOB_ID_BOOT, ComponentName(context, RebootRecoveryJob::class.java))
                .setRequiredNetwork(wifiNetworkRequest())
                .setPersisted(false)
                .build()
            scheduler.schedule(info)
        }

        fun scheduleDaily(context: Context) {
            if (Build.VERSION.SDK_INT < 30) return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID_DAILY) != null) return
            val info = JobInfo.Builder(JOB_ID_DAILY, ComponentName(context, RebootRecoveryJob::class.java))
                .setRequiredNetwork(wifiNetworkRequest())
                .setPeriodic(TimeUnit.DAYS.toMillis(1))
                .setPersisted(true)
                .build()
            scheduler.schedule(info)
        }

        fun cancelAll(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.cancel(JOB_ID_BOOT)
            scheduler.cancel(JOB_ID_DAILY)
        }

        private fun wifiNetworkRequest(): NetworkRequest =
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
    }
}

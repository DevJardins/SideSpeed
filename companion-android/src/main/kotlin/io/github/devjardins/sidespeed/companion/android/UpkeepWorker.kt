package io.github.devjardins.sidespeed.companion.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.devjardins.sidespeed.companion.upkeep.Trigger
import java.util.concurrent.TimeUnit

class UpkeepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as CompanionApplication).graph
        val report = graph.upkeep.runOnce(Trigger.BACKGROUND)
        if (graph.store.settings().notifications) {
            report.restartedNames.forEach { graph.notifier.restarted(it) }
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "sidespeed-upkeep"

        /** Only 15 minutes or 1 hour are offered; anything else is treated as 15. */
        fun periodMinutes(intervalMinutes: Int): Long = if (intervalMinutes == 60) 60L else 15L

        fun policy(replace: Boolean): ExistingPeriodicWorkPolicy =
            if (replace) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP

        /** [replace] when the interval changed; otherwise an existing schedule is kept. */
        fun schedule(context: Context, intervalMinutes: Int, replace: Boolean) {
            val minutes = periodMinutes(intervalMinutes)
            val request = PeriodicWorkRequestBuilder<UpkeepWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                policy(replace),
                request,
            )
        }
    }
}

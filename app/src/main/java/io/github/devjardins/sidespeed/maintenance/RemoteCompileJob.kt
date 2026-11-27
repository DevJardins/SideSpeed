package io.github.devjardins.sidespeed.maintenance

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.devjardins.sidespeed.SideSpeedApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Awaits engine drain until the queue is empty or the helper is down, bounded by [timeoutMs]. */
internal suspend fun awaitRemoteCompileDrain(
    state: Flow<MaintenanceState>,
    timeoutMs: Long = RemoteCompileJob.MAX_RUN_MS,
) {
    withTimeoutOrNull(timeoutMs) {
        state.first { it.queueLength == 0 || it.helperDown }
    }
}

/**
 * Holds a short JobScheduler execution window when a remote request wakes a cold process
 * and Android 12+ background restrictions refuse [MaintenanceService].
 */
class RemoteCompileJob : JobService() {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> },
    )
    private val jobLock = Any()
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as? SideSpeedApp)?.container
        if (container == null) {
            jobFinished(params, false)
            return false
        }
        synchronized(jobLock) {
            activeJob?.cancel()
            activeJob = scope.launch {
                val self = coroutineContext[Job]
                try {
                    awaitRemoteCompileDrain(container.maintenance.state)
                } finally {
                    synchronized(jobLock) {
                        if (activeJob === self) activeJob = null
                    }
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        synchronized(jobLock) {
            activeJob?.cancel()
            activeJob = null
        }
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val JOB_ID_REMOTE_COMPILE = 4103
        internal const val MAX_RUN_MS = 9 * 60_000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(
                JOB_ID_REMOTE_COMPILE,
                ComponentName(context, RemoteCompileJob::class.java),
            )
                .setOverrideDeadline(0L)
                .setPersisted(false)
                .build()
            scheduler.schedule(info)
        }
    }
}

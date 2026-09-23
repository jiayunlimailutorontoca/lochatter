package ink.jvm.chatter.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import ink.jvm.chatter.ChatterApp
import ink.jvm.chatter.data.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Belt and braces next to the alarm watchdog: a persisted periodic job (every 15 min, needs network).
 * Jobs survive reboots and are scheduled by a different subsystem than alarms, so a ROM that
 * throttles one often leaves the other alone. While the job runs, the process is alive and may
 * connect, catch up, and post notifications even if it is not allowed to start the foreground service.
 */
class KeepAliveJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as ChatterApp
        if (!app.prefs.loggedIn) return false
        ink.jvm.chatter.util.Diag.log(TAG, "keep-alive job")
        ChatService.start(this)
        app.repo.connect()
        app.repo.probe()
        scope.launch {
            // Stay alive until the socket is up and the catch-up sync had a moment to land.
            withTimeoutOrNull(25_000) { app.repo.connection.first { it == WsClient.State.CONNECTED } }
            delay(3_000)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeepAliveJob"
        private const val JOB_ID = 1701

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, KeepAliveJob::class.java))
                .setPeriodic(JobInfo.getMinPeriodMillis())
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(info) }.onFailure { Log.w(TAG, "schedule failed: $it") }
        }

        fun cancel(ctx: Context) {
            runCatching { ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID) }
        }
    }
}

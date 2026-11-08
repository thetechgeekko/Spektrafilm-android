/*
 * Spektrafilm for Android. GPLv3. Film modeling powered by spektrafilm (GPLv3).
 */
package com.spectrafilm.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Prevents a delayed completion command for export N from stopping the service after export N+1
 * has started. Service start IDs protect command ordering; this gate protects export ordering.
 */
internal class ExportForegroundServiceGenerationGate {
    private var newestStartedRunId = Long.MIN_VALUE

    fun recordStart(runId: Long) {
        if (runId > newestStartedRunId) newestStartedRunId = runId
    }

    fun mayStop(runId: Long): Boolean = runId >= newestStartedRunId
}

/**
 * The service-owned stop decision (#153): the service watches [ExportWorkRuntime.state] and
 * stops itself, so no external completion command can race the 5-second startForeground window.
 * Foreground priority is held exactly while work runs — a retained terminal result does not
 * need it — and a stale terminal from an older generation never stops the service protecting
 * a newer export.
 */
internal fun exportForegroundStopDecision(
    state: ExportRuntimeState,
    gate: ExportForegroundServiceGenerationGate,
): Boolean = when (state) {
    ExportRuntimeState.Idle -> true
    is ExportRuntimeState.Running -> false
    is ExportRuntimeState.Finished -> gate.mayStop(state.runId)
}

/**
 * Keeps a running export alive when the user leaves the app.
 *
 * ## Why this exists: kill-resistance, NOT speed
 *
 * A long backgrounded export is a kill candidate for Samsung's device-health
 * manager (`com.sec.android.sdhms`). Measured on SM-S948W, this service is what
 * stops that:
 *
 * ```
 *   with the service running:  oom_score_adj =  50
 *   after it stops:            oom_score_adj = 700
 * ```
 *
 * That is the whole of its value, and it is real — it is the fix for #153.
 *
 * ## It does NOT fix the 4x background slowdown. Measured, not assumed.
 *
 * This service was written believing it would. It does not, and the device run
 * that proved it also explained why. Backgrounding still costs ~3.9x (backgrounded
 * median 55063 ms vs foreground 14102 ms over 7 runs) even though the service
 * starts every single time and the notification posts every single time.
 *
 * The cpuset is the mechanism, and it predicts the result exactly — but a
 * foreground service does not determine which cpuset the process lands in:
 *
 * ```
 *   cpu0-5  max 3.63 GHz        cpu6-7  max 4.74 GHz   (prime pair)
 *
 *   /top-app          cpus=0-7      <- 14214 ms   foreground
 *   /foreground-boost cpus=0-7      <- 14927 ms   transient interaction boost
 *   /foreground       cpus=0-5      <- no prime cores; best an FGS normally rates
 *   /moderate         cpus=0-1,4-5  <- 55357 ms   where backgrounded exports land
 *   /background       cpus=0-1,4-5  <- identical mask to /moderate
 * ```
 *
 * `/moderate` is byte-identical to `/background` in CPU terms: half the cores and
 * zero prime cores. **No service-tier cpuset on this device contains cpu6/7**, so
 * changing `foregroundServiceType` cannot rescue it either. The perf half of #153
 * is not solvable with a foreground service on this hardware.
 *
 * The 4x is also only ever paid while the user leaves the app — the foreground
 * path was never slow. The remaining honest lever is the ~14 s foreground export.
 *
 * ## Ownership split
 *
 * It runs no rendering code of its own. [ExportWorkRuntime]'s process-owned coroutine
 * owns the export across Activity recreation; this service only holds the process's
 * lifecycle/scheduling class while that work runs. The service therefore remains off
 * the render path and carries no image-parity risk.
 *
 * Every failure path is swallowed: an export that would have succeeded slowly must
 * never fail because a notification channel or an OEM background-start rule said no.
 */
class ExportForegroundService : Service() {
    private val generationGate = ExportForegroundServiceGenerationGate()
    // Lazy: the Service's application context, and so its AppContainer, exists only once
    // attached, after property initialisers run.
    private val scope by lazy { CoroutineScope(SupervisorJob() + appContainer.dispatchers.mainImmediate) }
    private var observer: Job? = null
    private var progressObserver: Job? = null
    // The run the notification currently describes, so a progress update rebuilds it intact.
    private var notifiedRunId = INVALID_RUN_ID
    private var notifiedStartedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * API 35+ runs this service as mediaProcessing, which the system caps at roughly six hours
     * per day and calls this when the budget is spent; the service must stop promptly or the process ANRs. The export
     * itself is process-owned by [ExportWorkRuntime] and keeps running exactly as it does when
     * [startForeground] is refused: at background priority, never failed by the service.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Diag.w("export foreground service timed out (type=$fgsType); export continues at background priority")
        stopSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTimeout(startId: Int) {
        onTimeout(startId, 0)
    }

    /**
     * Tries each type from [foregroundServiceTypesFor] in order and stops at the first the
     * system accepts. A refused type throws without making the service foreground, so the
     * next one can still be tried inside the same start window. Returns false when every
     * type was refused.
     */
    private fun enterForeground(notification: Notification): Boolean {
        val refusedBefore = refusedTypes
        var refusedNow = 0
        for (type in typesToTry(Build.VERSION.SDK_INT, refusedBefore)) {
            try {
                startForegroundAs(notification, type)
                // Remember a refusal only once a later type has been accepted, so a start
                // that fails outright (background-start rules) never narrows the next try.
                if (refusedNow != 0) {
                    refusedTypes = refusedBefore or refusedNow
                    Diag.w("export foreground service runs as type=$type; skipping refused types for this process")
                }
                return true
            } catch (t: Throwable) {
                refusedNow = refusedNow or type
                Diag.w("export foreground service could not start as type=$type: ${t.message}")
            }
        }
        return false
    }

    /**
     * Calls the platform's startForeground directly instead of ServiceCompat. On API 34+
     * ServiceCompat ANDs the type with a mask frozen at Android 14
     * (FOREGROUND_SERVICE_TYPE_ALLOWED_SINCE_U, still so in androidx-main), which does not
     * contain mediaProcessing (added in API 35). 8192 & mask == 0, so the platform received
     * type NONE and refused it as "Starting FGS with type none ... has been prohibited" on
     * every export (#458): the refusal was ours, not the phone's policy.
     */
    private fun startForegroundAs(notification: Notification, type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runId = intent?.getLongExtra(EXTRA_RUN_ID, INVALID_RUN_ID) ?: INVALID_RUN_ID
        val startedAt = intent?.getLongExtra(EXTRA_STARTED_AT, 0L) ?: 0L
        // Must reach startForeground within ~5 s of startForegroundService or the
        // system throws; doing it first thing in onStartCommand is well inside that.
        // Building the notification touches NotificationManager (channel creation), which can
        // throw too; keep it inside the same failure boundary so it never crashes the process
        // that owns the export.
        val notification = try {
            buildNotification(runId, startedAt)
        } catch (t: Throwable) {
            Diag.w("export foreground notification could not be built: ${t.message}")
            null
        }
        if (notification == null || !enterForeground(notification)) {
            // API 31+ can refuse a background start, and OEM policies vary. The export
            // still runs — just at background priority, i.e. how it behaved before.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> {
                if (runId > INVALID_RUN_ID) generationGate.recordStart(runId)
                notifiedRunId = runId
                notifiedStartedAt = startedAt
                ensureRuntimeObserver()
            }
            ACTION_CANCEL -> {
                // The notification's Cancel is service-owned: it asks the process runtime to
                // cancel; the observer below sees the terminal transition and stops the
                // service. Never stop directly here — the work must reach its terminal state
                // and durable checkpoint first.
                if (runId > INVALID_RUN_ID && !ExportWorkRuntime.cancel(runId)) {
                    Diag.w("export cancel ignored: run $runId is no longer running")
                }
                ensureRuntimeObserver()
            }
            else -> {
                Diag.w("export foreground service received an invalid command")
                stopSelfResult(startId)
            }
        }
        // Not sticky: if the process dies the export died with it. The durable editor
        // cursor (#139) and the journaled MediaStore transaction reconcile the loss on
        // the next launch; restarting a service with no work would only show a
        // notification for nothing.
        return START_NOT_STICKY
    }

    /**
     * The service owns its lifetime by observing the process runtime (#153): it stays
     * foreground exactly while the tracked export runs and stops itself on the export's
     * terminal transition. Self-stopping after startForeground cannot race the 5-second
     * window, which is what the old externally-queued stop command existed to dodge.
     */
    private fun ensureRuntimeObserver() {
        if (observer?.isActive == true) return
        observer = scope.launch {
            ExportWorkRuntime.state.collect { state ->
                if (exportForegroundStopDecision(state, generationGate)) {
                    stopSelf()
                }
            }
        }
        ensureProgressObserver()
    }

    /**
     * #466: re-posts the ongoing notification with the stage-weighted fraction. Progress only
     * moves when a stage starts (a handful of updates per export), so this needs no throttle.
     * Without the notification permission (API 33+) the update is skipped; the service stays
     * foreground either way.
     */
    private fun ensureProgressObserver() {
        if (progressObserver?.isActive == true) return
        progressObserver = scope.launch {
            ExportWorkRuntime.progress.collect { fraction ->
                if (fraction <= 0f || ExportWorkRuntime.state.value !is ExportRuntimeState.Running) return@collect
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@ExportForegroundService, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    return@collect
                }
                try {
                    NotificationManagerCompat.from(this@ExportForegroundService)
                        .notify(NOTIFICATION_ID, buildNotification(notifiedRunId, notifiedStartedAt, fraction))
                } catch (t: Throwable) {
                    Diag.w("export progress notification update failed: ${t.message}")
                }
            }
        }
    }

    private fun buildNotification(runId: Long, startedAtMillis: Long, fraction: Float = 0f): Notification {
        ensureChannel(this)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.tool_export_notification_text))
            .setSmallIcon(R.drawable.ic_stat_spektrafilm)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Determinate once a stage has been reported (#466, ExportProgress.kt): the
            // fraction is the summed weight of the stages already started, so it never
            // claims work inside a stage. Indeterminate before that, and for exports that
            // report no stages (batch, half-frame pair). The chronometer shows elapsed time.
            .apply {
                if (fraction > 0f) {
                    setProgress(PROGRESS_MAX, (fraction * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
        if (startedAtMillis > 0L) {
            builder.setWhen(startedAtMillis).setShowWhen(false).setUsesChronometer(true)
        }
        if (runId > INVALID_RUN_ID) {
            builder.addAction(
                0,
                getString(R.string.tool_cancel),
                PendingIntent.getService(
                    this,
                    runId.toInt(),
                    commandIntent(this, ACTION_CANCEL, runId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        return builder.build()
    }

    companion object {
        /**
         * The foreground-service type to start with on [sdkInt]: mediaProcessing on API 35+
         * (the type meant for long media renders), dataSync on 29-34 where mediaProcessing does
         * not exist, and none before 29, where startForeground takes no type. Must stay a
         * subset of the manifest's `android:foregroundServiceType`.
         */
        internal fun foregroundServiceTypeFor(sdkInt: Int): Int = foregroundServiceTypesFor(sdkInt).first()

        /**
         * Every type to try on [sdkInt], preferred first: mediaProcessing then dataSync on
         * API 35+ (dataSync as a fallback if a build refuses mediaProcessing), dataSync alone on 29-34, and
         * untyped before 29.
         */
        internal fun foregroundServiceTypesFor(sdkInt: Int): List<Int> = when {
            sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            sdkInt >= Build.VERSION_CODES.Q -> listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> listOf(0)
        }

        /**
         * Types the platform refused in this process while a later type was accepted, so
         * later exports skip straight to the type that works instead of throwing and logging
         * each time. Per process: a new process tries the preferred type again. (The refusal
         * that motivated this, #458, turned out to be ServiceCompat zeroing mediaProcessing;
         * see [startForegroundAs]. This stays as a guard for a genuine platform refusal.)
         */
        @Volatile private var refusedTypes = 0

        /**
         * [foregroundServiceTypesFor] minus the types in [refused]. Never empty: if every
         * typed choice was refused, the full list is tried again rather than none.
         */
        internal fun typesToTry(sdkInt: Int, refused: Int): List<Int> {
            val all = foregroundServiceTypesFor(sdkInt)
            return all.filter { it == 0 || it and refused == 0 }.ifEmpty { all }
        }

        private const val PROGRESS_MAX = 100
        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.spectrafilm.app.action.START_EXPORT_FOREGROUND"
        private const val ACTION_CANCEL = "com.spectrafilm.app.action.CANCEL_EXPORT"
        private const val EXTRA_RUN_ID = "com.spectrafilm.app.extra.EXPORT_RUN_ID"
        private const val EXTRA_STARTED_AT = "com.spectrafilm.app.extra.EXPORT_STARTED_AT"
        private const val INVALID_RUN_ID = 0L

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.tool_export_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = ctx.getString(R.string.tool_export_channel_description)
                    setShowBadge(false)
                },
            )
        }

        /**
         * Enter the foreground scheduling group. Safe to call when already running.
         * There is deliberately no external stop command: the service observes
         * [ExportWorkRuntime.state] and stops itself on the export's terminal transition.
         *
         * Never throws: if the service cannot start, the export proceeds at whatever
         * priority the scheduler gives it, which is the pre-existing behaviour.
         */
        fun start(ctx: Context, runId: Long, startedAtMillis: Long) {
            try {
                ContextCompat.startForegroundService(
                    ctx.applicationContext,
                    commandIntent(ctx, ACTION_START, runId)
                        .putExtra(EXTRA_STARTED_AT, startedAtMillis),
                )
            } catch (t: Throwable) {
                Diag.w("export foreground service start refused: ${t.message}")
            }
        }

        private fun commandIntent(ctx: Context, action: String, runId: Long): Intent =
            Intent(ctx.applicationContext, ExportForegroundService::class.java)
                .setAction(action)
                .putExtra(EXTRA_RUN_ID, runId)
    }
}

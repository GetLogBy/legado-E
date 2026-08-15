package io.legado.app.help.sync.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.legado.app.help.sync.SyncConfig
import java.util.concurrent.TimeUnit

/**
 * WorkManager 周期同步调度
 */
object WorkManagerHelper {

    private const val UNIQUE_PERIODIC = "sync_periodic_work"
    private const val UNIQUE_LOOP = "sync_loop_work"

    /** WorkManager 周期任务最短周期(分钟), 低于此值用一次性任务自续期循环 */
    private const val MIN_PERIODIC = 15L

    /**
     * 按设置调度周期同步, 设置变更时重新调度
     * 间隔 >= 15 分钟: PeriodicWorkRequest; < 15 分钟(即时/5/10): OneTimeWorkRequest 自续期循环
     */
    fun schedule(context: Context) {
        if (!SyncConfig.enabled) {
            cancel(context)
            return
        }
        val interval = SyncConfig.intervalMinutes.toLong()
        val wm = WorkManager.getInstance(context)
        if (interval >= MIN_PERIODIC) {
            wm.cancelUniqueWork(UNIQUE_LOOP)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } else {
            wm.cancelUniqueWork(UNIQUE_PERIODIC)
            enqueueLoop(wm)
        }
    }

    /**
     * 续期下一次循环同步, 由 SyncWorker 同步成功后调用
     */
    fun scheduleNext(context: Context) {
        if (!SyncConfig.enabled) return
        if (SyncConfig.intervalMinutes >= MIN_PERIODIC) return
        enqueueLoop(WorkManager.getInstance(context))
    }

    private fun enqueueLoop(wm: WorkManager) {
        val interval = SyncConfig.intervalMinutes.toLong().coerceAtLeast(0)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniqueWork(
            UNIQUE_LOOP,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_PERIODIC)
        wm.cancelUniqueWork(UNIQUE_LOOP)
    }
}
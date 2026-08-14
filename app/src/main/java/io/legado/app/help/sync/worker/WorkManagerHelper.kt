package io.legado.app.help.sync.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.legado.app.help.sync.SyncConfig
import java.util.concurrent.TimeUnit

/**
 * WorkManager 周期同步调度
 */
object WorkManagerHelper {

    private const val UNIQUE_NAME = "sync_periodic_work"

    /** 最短周期(分钟), WorkManager 周期任务下限为 15 分钟 */
    private const val MIN_INTERVAL = 15L

    /**
     * 按设置调度周期同步, 设置变更时重新调度
     */
    fun schedule(context: Context) {
        if (!SyncConfig.enabled) {
            cancel(context)
            return
        }
        val interval = SyncConfig.intervalMinutes.coerceAtLeast(MIN_INTERVAL)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
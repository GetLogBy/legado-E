package io.legado.app.help.sync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.legado.app.help.sync.SyncManager

/**
 * 周期同步 Worker, 由 WorkManager 调度
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return if (SyncManager.syncWorker()) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
package io.legado.app.help.sync

import io.legado.app.help.AppWebDav
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.onError
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx

/**
 * 同步管理器: 串行执行同步任务
 */
object SyncManager {

    private val syncMutex = Mutex()

    var isSyncing = false
        private set

    /**
     * 立即同步 (拉取 + 推送), 串行执行
     */
    fun syncNow(onFinish: (() -> Unit)? = null) {
        Coroutine.async {
            syncAll()
        }.onError {
            it.printOnDebug()
            it.toastOnUi()
            SyncLedger.lastResult = "同步失败: ${it.localizedMessage}"
        }.invokeOnCompletion {
            onFinish?.invoke()
        }
    }

    /**
     * App 启动时拉取
     */
    fun syncOnStart() {
        if (!SyncConfig.enabled) return
        Coroutine.async {
            pullOnly()
        }.onError {
            it.printOnDebug()
        }
    }

    /**
     * App 退到后台时推送
     */
    fun syncOnStop() {
        if (!SyncConfig.enabled) return
        Coroutine.async {
            pushOnly()
        }.onError {
            it.printOnDebug()
        }
    }

    /**
     * WorkManager 周期任务挂起入口
     */
    suspend fun syncWorker(): Boolean {
        if (!SyncConfig.enabled) return true
        return kotlin.runCatching {
            syncAll()
        }.isSuccess
    }

    private suspend fun pullOnly() {
        withLockAndRun {
            SyncClient.pullAll()
            ConflictResolver.autoResolveAllPending()
        }
    }

    private suspend fun pushOnly() {
        withLockAndRun {
            SyncClient.pushAll()
        }
    }

    private suspend fun syncAll() {
        withLockAndRun {
            SyncClient.pullAll()
            ConflictResolver.autoResolveAllPending()
            SyncClient.pushAll()
        }
    }

    private suspend fun withLockAndRun(block: suspend () -> Unit) {
        syncMutex.withLock {
            isSyncing = true
            kotlin.runCatching {
                if (!AppWebDav.isOk) {
                    AppWebDav.upConfig()
                }
                if (!AppWebDav.isOk) {
                    throw NoSuchElementException("webDav没有配置")
                }
                block()
                SyncLedger.lastPushTime = System.currentTimeMillis()
                SyncLedger.lastPullAt = System.currentTimeMillis()
                SyncLedger.lastResult = "最近同步成功 ${SyncLedger.lastPushTime.formatTime()}"
            }.onFailure {
                SyncLedger.lastResult = "同步失败: ${it.localizedMessage}"
                throw it
            }.also {
                isSyncing = false
            }
        }
    }

    private fun Long.formatTime(): String {
        val date = java.util.Date(this)
        return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(date)
    }
}

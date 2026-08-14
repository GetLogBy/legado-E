package io.legado.app.help.sync

import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.putLong

/**
 * 同步状态记录, 持久化到 LocalConfig 共享参数
 */
object SyncLedger {

    private const val keyLastPull = "sync_last_pull"
    private const val keyLastPush = "sync_last_push"
    private const val keyLastPullByType = "sync_last_pull_by_type"
    private const val keyLastResult = "sync_last_result"

    /** 最近一次成功 pull 的时间 */
    var lastPullAt: Long
        get() = LocalConfig.getLong(keyLastPull, 0)
        set(value) = LocalConfig.putLong(keyLastPull, value)

    /** 最近一次成功 push 的时间 */
    var lastPushTime: Long
        get() = LocalConfig.getLong(keyLastPush, 0)
        set(value) = LocalConfig.putLong(keyLastPush, value)

    /** 最近一次同步结果描述 */
    var lastResult: String?
        get() = LocalConfig.getString(keyLastResult, null)
        set(value) {
            if (value != null) {
                LocalConfig.putString(keyLastResult, value)
            } else {
                LocalConfig.remove(keyLastResult)
            }
        }

    /** 单类型 pull 游标, 记录该类型上次同步到的时间戳 */
    fun lastPullByType(type: DataSyncType): Long {
        return LocalConfig.getLong("$keyLastPullByType${type.name}", 0)
    }

    fun setLastPullByType(type: DataSyncType, time: Long) {
        LocalConfig.putLong("$keyLastPullByType${type.name}", time)
    }
}

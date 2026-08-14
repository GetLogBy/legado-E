package io.legado.app.help.sync

import io.legado.app.help.config.AppConfig

/**
 * 读取同步相关设置
 */
object SyncConfig {

    val enabled get() = AppConfig.syncEnabled

    val intervalMinutes get() = AppConfig.syncInterval

    /** 启用的数据类型 */
    val dataTypes: Set<DataSyncType>
        get() = AppConfig.syncDataTypes.mapNotNull {
            DataSyncType.fromName(it)
        }.toSet()

    /** 默认冲突策略 local/cloud/manual */
    val conflictMode get() = AppConfig.syncConflictMode

    fun isTypeEnabled(type: DataSyncType): Boolean = type in dataTypes
}

package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 墓碑记录,标记本地已删除的记录,推送成功后清除
 */
@Entity(tableName = "sync_tombstones", primaryKeys = ["tableName", "recordKey"])
data class SyncTombstone(
    var tableName: String = "",
    var recordKey: String = "",
    @ColumnInfo(defaultValue = "0")
    var deletedAt: Long = System.currentTimeMillis()
)
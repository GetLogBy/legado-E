package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 冲突记录,同一记录本地与云端在冲突窗口内都发生过修改
 */
@Entity(tableName = "sync_conflicts")
data class SyncConflict(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    var tableName: String = "",
    var recordKey: String = "",
    @ColumnInfo(defaultValue = "0")
    var localModified: Long = 0,
    @ColumnInfo(defaultValue = "0")
    var cloudModified: Long = 0,
    var localJson: String? = null,
    var cloudJson: String? = null,
    // 0 待处理 1 已解决
    @ColumnInfo(defaultValue = "0")
    var status: Int = 0,
    @ColumnInfo(defaultValue = "0")
    var createdAt: Long = System.currentTimeMillis()
)
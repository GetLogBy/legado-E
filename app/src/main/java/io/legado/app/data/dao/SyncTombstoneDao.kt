package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.SyncTombstone

@Dao
interface SyncTombstoneDao {

    @Query("SELECT * FROM sync_tombstones")
    fun all(): List<SyncTombstone>

    @Query("SELECT * FROM sync_tombstones WHERE tableName = :tableName")
    fun getByTable(tableName: String): List<SyncTombstone>

    @Query(
        "SELECT * FROM sync_tombstones WHERE tableName = :tableName AND recordKey = :recordKey"
    )
    fun get(tableName: String, recordKey: String): SyncTombstone?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(tombstone: SyncTombstone)

    @Query("DELETE FROM sync_tombstones WHERE tableName = :tableName AND recordKey = :recordKey")
    fun delete(tableName: String, recordKey: String)

    @Query("DELETE FROM sync_tombstones")
    fun clear()
}
package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.SyncConflict
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncConflictDao {

    @Query("SELECT * FROM sync_conflicts ORDER BY createdAt DESC")
    fun flowAll(): List<SyncConflict>

    @Query("SELECT * FROM sync_conflicts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SyncConflict>>

    @Query("SELECT * FROM sync_conflicts WHERE status = 0 ORDER BY createdAt DESC")
    fun pending(): List<SyncConflict>

    @get:Query("SELECT COUNT(*) FROM sync_conflicts WHERE status = 0")
    val pendingCount: Int

    @Query(
        "SELECT * FROM sync_conflicts WHERE tableName = :tableName AND recordKey = :recordKey AND status = 0"
    )
    fun get(tableName: String, recordKey: String): SyncConflict?

    @Insert
    fun insert(conflict: SyncConflict)

    @Update
    fun update(conflict: SyncConflict)

    @Delete
    fun delete(conflict: SyncConflict)

    @Query("DELETE FROM sync_conflicts WHERE status = 1")
    fun clearResolved()

    @Query("DELETE FROM sync_conflicts")
    fun clear()
}
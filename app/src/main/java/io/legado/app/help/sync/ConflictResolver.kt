package io.legado.app.help.sync

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.SyncConflict
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.sha1Encode
import java.io.File

/**
 * 冲突检测与解决
 *
 * 冲突模式:
 * - manual: 入冲突表等待用户手动处理
 * - keepLocal: 保留本地
 * - keepCloud: 保留云端
 */
object ConflictResolver {

    /**
     * 冲突记录到冲突表 (仅 manual 模式)
     */
    suspend fun recordConflict(file: WebDavFile, local: Any?) {
        val tableName = tableNameOf(file)
        val recordKey = keyOf(file, local)
        if (recordKey == null) return
        val localJson = local?.let { GSON.toJson(it) }
        val cloudJson = kotlin.runCatching {
            String(file.download())
        }.getOrNull()
        val conflict = SyncConflict(
            tableName = tableName,
            recordKey = recordKey,
            localModified = local?.localModified() ?: 0,
            cloudModified = file.lastModify,
            localJson = localJson,
            cloudJson = cloudJson,
            status = 0,
            createdAt = System.currentTimeMillis()
        )
        // 已存在待处理冲突则不重复插入
        if (appDb.syncConflictDao.get(tableName, recordKey) == null) {
            appDb.syncConflictDao.insert(conflict)
        }
    }

    /** 按默认策略自动解决 */
    suspend fun autoResolve(conflict: SyncConflict) {
        when (SyncConfig.conflictMode) {
            "keepLocal" -> keepLocal(conflict)
            "keepCloud" -> keepCloud(conflict)
            else -> {
                // manual 不处理, 等待用户
            }
        }
    }

    /** 自动策略下, 解决所有待处理冲突 */
    suspend fun autoResolveAllPending() {
        if (SyncConfig.conflictMode == "manual") return
        appDb.syncConflictDao.pending().forEach {
            autoResolve(it)
        }
    }

    /** 保留本地: 标记已解决, 本地数据会在下次 push 覆盖云端 */
    suspend fun keepLocal(conflict: SyncConflict) {
        when (conflict.tableName) {
            DataSyncType.BOOKS.tableName -> {
                appDb.bookDao.getBook(conflict.recordKey)?.let {
                    appDb.bookDao.markSynced(it.bookUrl, it.localModified)
                }
            }

            DataSyncType.BOOKMARKS.tableName -> {
                conflict.recordKey.toLongOrNull()?.let { time ->
                    appDb.bookmarkDao.getByTime(time)?.let {
                        appDb.bookmarkDao.markSynced(it.time, it.localModified)
                    }
                }
            }

            DataSyncType.BOOK_GROUPS.tableName -> {
                conflict.recordKey.toLongOrNull()?.let { groupId ->
                    appDb.bookGroupDao.getByID(groupId)?.let {
                        appDb.bookGroupDao.markSynced(it.groupId, it.localModified)
                    }
                }
            }

            DataSyncType.READ_RECORDS.tableName -> {
                val (deviceId, bookName) = conflict.recordKey.splitRecordKey()
                appDb.readRecordDao.get(deviceId, bookName)?.let {
                    appDb.readRecordDao.markSynced(it.deviceId, it.bookName, it.localModified)
                }
            }
        }
        conflict.status = 1
        appDb.syncConflictDao.update(conflict)
    }

    /** 保留云端: 用 cloudJson 覆盖本地 */
    suspend fun keepCloud(conflict: SyncConflict) {
        val cloudJson = conflict.cloudJson
        if (cloudJson != null) {
            applyCloudJson(conflict.tableName, cloudJson)
        }
        conflict.status = 1
        appDb.syncConflictDao.update(conflict)
    }

    /**
     * 保留两者: 本地保留本地版本(不上推), 云端文件原样保留.
     * 把记录标记为与云端时间点一致, 消除冲突, 两端各保留一份
     */
    suspend fun keepBoth(conflict: SyncConflict) {
        val mark = maxOf(conflict.localModified, conflict.cloudModified)
        when (conflict.tableName) {
            DataSyncType.BOOKS.tableName -> {
                appDb.bookDao.getBook(conflict.recordKey)?.let {
                    appDb.bookDao.markSynced(it.bookUrl, mark)
                }
            }

            DataSyncType.BOOKMARKS.tableName -> {
                conflict.recordKey.toLongOrNull()?.let { time ->
                    appDb.bookmarkDao.getByTime(time)?.let {
                        appDb.bookmarkDao.markSynced(it.time, mark)
                    }
                }
            }

            DataSyncType.BOOK_GROUPS.tableName -> {
                conflict.recordKey.toLongOrNull()?.let { groupId ->
                    appDb.bookGroupDao.getByID(groupId)?.let {
                        appDb.bookGroupDao.markSynced(it.groupId, mark)
                    }
                }
            }

            DataSyncType.READ_RECORDS.tableName -> {
                val (deviceId, bookName) = conflict.recordKey.splitRecordKey()
                appDb.readRecordDao.get(deviceId, bookName)?.let {
                    appDb.readRecordDao.markSynced(it.deviceId, it.bookName, mark)
                }
            }
        }
        conflict.status = 1
        appDb.syncConflictDao.update(conflict)
    }

    private fun applyCloudJson(tableName: String, cloudJson: String) {
        when (tableName) {
            DataSyncType.BOOKS.tableName -> {
                GSON.fromJsonObject<Book>(cloudJson).getOrNull()?.let {
                    it.localModified = it.cloudModified
                    appDb.bookDao.insert(it)
                    appDb.bookDao.markSynced(it.bookUrl, it.cloudModified)
                }
            }

            DataSyncType.BOOKMARKS.tableName -> {
                GSON.fromJsonObject<Bookmark>(cloudJson).getOrNull()?.let {
                    it.localModified = it.cloudModified
                    appDb.bookmarkDao.insert(it)
                    appDb.bookmarkDao.markSynced(it.time, it.cloudModified)
                }
            }

            DataSyncType.BOOK_GROUPS.tableName -> {
                GSON.fromJsonObject<BookGroup>(cloudJson).getOrNull()?.let {
                    it.localModified = it.cloudModified
                    appDb.bookGroupDao.insert(it)
                    appDb.bookGroupDao.markSynced(it.groupId, it.cloudModified)
                }
            }

            DataSyncType.READ_RECORDS.tableName -> {
                GSON.fromJsonObject<ReadRecord>(cloudJson).getOrNull()?.let {
                    it.localModified = it.cloudModified
                    appDb.readRecordDao.insert(it)
                    appDb.readRecordDao.markSynced(it.deviceId, it.bookName, it.cloudModified)
                }
            }
        }
    }

    // ============================ 工具 ============================

    private fun tableNameOf(file: WebDavFile): String {
        val path = file.urlName
        return when {
            path.contains("/books/") -> DataSyncType.BOOKS.tableName
            path.contains("/bookmarks/") -> DataSyncType.BOOKMARKS.tableName
            path.contains("/bookGroups/") -> DataSyncType.BOOK_GROUPS.tableName
            path.contains("/readRecords/") -> DataSyncType.READ_RECORDS.tableName
            else -> DataSyncType.BOOKS.tableName
        }
    }

    private fun keyOf(file: WebDavFile, local: Any?): String? {
        return when (local) {
            is Book -> local.bookUrl
            is Bookmark -> local.time.toString()
            is BookGroup -> local.groupId.toString()
            is ReadRecord -> "${local.deviceId}|${local.bookName}"
            else -> null
        }
    }

    private fun Any.localModified(): Long = when (this) {
        is Book -> localModified
        is Bookmark -> localModified
        is BookGroup -> localModified
        is ReadRecord -> localModified
        else -> 0
    }

    private fun String.splitRecordKey(): Pair<String, String> {
        val index = indexOf('|')
        return if (index > 0) {
            substring(0, index) to substring(index + 1)
        } else {
            "" to this
        }
    }
}

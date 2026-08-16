package io.legado.app.help.sync

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.SyncTombstone
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.sha1Encode

/**
 * WebDAV 传输层: 每个数据类型的 push/pull
 *
 * 云端布局:
 * - books/<sha1(bookUrl)>.json
 * - bookmarks/<time>.json
 * - bookGroups/<groupId>.json
 * - readRecords/<sha1(deviceId+bookName)>.json
 * - readConfigs/<configFileName>.json (配置整文件)
 * - tombstones/<table>_<recordKey>.json
 */
object SyncClient {

    private val authorization: Authorization?
        get() = AppWebDav.authorization

    private val baseUrl get() = AppWebDav.syncRootUrl

    private fun dirUrl(type: DataSyncType) = "${baseUrl}${type.dirName}/"

    private fun tombstonesUrl() = "${baseUrl}tombstones/"

    private fun webDav(url: String): WebDav {
        return WebDav(url, authorization ?: throw NoSuchElementException("webDav没有配置"))
    }

    suspend fun isReady(): Boolean = authorization != null

    /** 确保所有云端目录存在 */
    suspend fun ensureDirs() {
        ensureDir(baseUrl)
        DataSyncType.values().forEach { type ->
            kotlin.runCatching {
                webDav(dirUrl(type)).makeAsDir()
            }
        }
        kotlin.runCatching {
            webDav(tombstonesUrl()).makeAsDir()
        }
    }

    /** 逐级创建目录, 兼容不支持自动创建父目录的服务器 */
    private suspend fun ensureDir(url: String) {
        val dav = webDav(url)
        if (dav.exists()) return
        val parent = url.trimEnd('/').substringBeforeLast('/', url) + "/"
        if (parent != url && parent.contains("://")) {
            ensureDir(parent)
        }
        dav.makeAsDir()
    }

    // ============================ push ============================

    suspend fun pushBooks() {
        appDb.bookDao.needPush.forEach { book ->
            val url = "${dirUrl(DataSyncType.BOOKS)}${book.bookUrl.sha1Encode()}.json"
            webDav(url).upload(GSON.toJson(book).toByteArray(), "application/json")
            appDb.bookDao.markSynced(book.bookUrl, book.localModified)
        }
    }

    suspend fun pushBookmarks() {
        appDb.bookmarkDao.needPush.forEach { bookmark ->
            val url = "${dirUrl(DataSyncType.BOOKMARKS)}${bookmark.time}.json"
            webDav(url).upload(GSON.toJson(bookmark).toByteArray(), "application/json")
            appDb.bookmarkDao.markSynced(bookmark.time, bookmark.localModified)
        }
    }

    suspend fun pushBookGroups() {
        appDb.bookGroupDao.needPush.forEach { group ->
            val url = "${dirUrl(DataSyncType.BOOK_GROUPS)}${group.groupId}.json"
            webDav(url).upload(GSON.toJson(group).toByteArray(), "application/json")
            appDb.bookGroupDao.markSynced(group.groupId, group.localModified)
        }
    }

    suspend fun pushReadRecords() {
        appDb.readRecordDao.needPush.forEach { record ->
            val key = recordKeyOf(record)
            val url = "${dirUrl(DataSyncType.READ_RECORDS)}${key.sha1Encode()}.json"
            webDav(url).upload(GSON.toJson(record).toByteArray(), "application/json")
            appDb.readRecordDao.markSynced(record.deviceId, record.bookName, record.localModified)
        }
    }

    /** push 墓碑 */
    suspend fun pushTombstones() {
        appDb.syncTombstoneDao.all().forEach { tombstone ->
            val url = tombstoneUrl(tombstone)
            webDav(url).upload(GSON.toJson(tombstone).toByteArray(), "application/json")
            appDb.syncTombstoneDao.delete(tombstone.tableName, tombstone.recordKey)
        }
    }

    /** 推送全部启用的类型 */
    suspend fun pushAll() {
        ensureDirs()
        SyncConfig.dataTypes.forEach { type ->
            when (type) {
                DataSyncType.BOOKS -> pushBooks()
                DataSyncType.BOOKMARKS -> pushBookmarks()
                DataSyncType.BOOK_GROUPS -> pushBookGroups()
                DataSyncType.READ_RECORDS -> pushReadRecords()
                DataSyncType.READ_CONFIGS -> pushReadConfigs()
            }
        }
        pushTombstones()
    }

    // ============================ pull ============================

    suspend fun pullBooks() {
        val lastPullAt = SyncLedger.lastPullByType(DataSyncType.BOOKS)
        val localBooks = appDb.bookDao.all
        listRemoteFiles(DataSyncType.BOOKS).forEach { file ->
            val bookUrl = file.matchLocalKey(localBooks) { it.bookUrl }
            val local = localBooks.firstOrNull { it.bookUrl == bookUrl }
            pullUpdate(file, local, lastPullAt) {
                GSON.fromJsonObject<Book>(it).getOrNull()?.let { cloudBook ->
                    applyBook(cloudBook, file.lastModify)
                }
            }
        }
        SyncLedger.setLastPullByType(DataSyncType.BOOKS, System.currentTimeMillis())
    }

    suspend fun pullBookmarks() {
        val lastPullAt = SyncLedger.lastPullByType(DataSyncType.BOOKMARKS)
        val localBookmarks = appDb.bookmarkDao.all
        listRemoteFiles(DataSyncType.BOOKMARKS).forEach { file ->
            val time = file.displayName.removeSuffix(".json").toLongOrNull()
            if (time != null) {
                val local = localBookmarks.firstOrNull { it.time == time }
                pullUpdate(file, local, lastPullAt) {
                    GSON.fromJsonObject<Bookmark>(it).getOrNull()?.let { cloudBookmark ->
                        applyBookmark(cloudBookmark, file.lastModify)
                    }
                }
            }
        }
        SyncLedger.setLastPullByType(DataSyncType.BOOKMARKS, System.currentTimeMillis())
    }

    suspend fun pullBookGroups() {
        val lastPullAt = SyncLedger.lastPullByType(DataSyncType.BOOK_GROUPS)
        val localGroups = appDb.bookGroupDao.all
        listRemoteFiles(DataSyncType.BOOK_GROUPS).forEach { file ->
            val groupId = file.displayName.removeSuffix(".json").toLongOrNull()
            if (groupId != null) {
                val local = localGroups.firstOrNull { it.groupId == groupId }
                pullUpdate(file, local, lastPullAt) {
                    GSON.fromJsonObject<BookGroup>(it).getOrNull()?.let { cloudGroup ->
                        applyBookGroup(cloudGroup, file.lastModify)
                    }
                }
            }
        }
        SyncLedger.setLastPullByType(DataSyncType.BOOK_GROUPS, System.currentTimeMillis())
    }

    suspend fun pullReadRecords() {
        val lastPullAt = SyncLedger.lastPullByType(DataSyncType.READ_RECORDS)
        val localRecords = appDb.readRecordDao.all
        listRemoteFiles(DataSyncType.READ_RECORDS).forEach { file ->
            val key = file.matchLocalKey(localRecords) { recordKeyOf(it) }
            val local = localRecords.firstOrNull { recordKeyOf(it) == key }
            pullUpdate(file, local, lastPullAt) {
                GSON.fromJsonObject<ReadRecord>(it).getOrNull()?.let { cloudRecord ->
                    applyReadRecord(cloudRecord, file.lastModify)
                }
            }
        }
        SyncLedger.setLastPullByType(DataSyncType.READ_RECORDS, System.currentTimeMillis())
    }

    /** 拉取墓碑并应用远端删除 */
    suspend fun pullTombstones() {
        listRemoteFiles(tombstonesUrl()).forEach { file ->
            file.download().let { bytes ->
                GSON.fromJsonObject<SyncTombstone>(String(bytes)).getOrNull()?.let { tombstone ->
                    applyTombstone(tombstone)
                }
            }
            // 应用完成后删除远端墓碑, 避免重复处理
            webDav(tombstonesUrl() + file.displayName).delete()
        }
    }

    /** 拉取全部启用的类型 */
    suspend fun pullAll() {
        ensureDirs()
        pullTombstones()
        SyncConfig.dataTypes.forEach { type ->
            when (type) {
                DataSyncType.BOOKS -> pullBooks()
                DataSyncType.BOOKMARKS -> pullBookmarks()
                DataSyncType.BOOK_GROUPS -> pullBookGroups()
                DataSyncType.READ_RECORDS -> pullReadRecords()
                DataSyncType.READ_CONFIGS -> pullReadConfigs()
            }
        }
    }

    // ============================ 冲突处理 ============================

    private suspend fun pullUpdate(
        file: WebDavFile,
        local: Any?,
        lastPullAt: Long,
        apply: suspend (String) -> Unit
    ) {
        val localCloudModified = local?.cloudModified() ?: 0
        val localLocalModified = local?.localModified() ?: 0
        // 云端比本地新
        if (file.lastModify > localCloudModified) {
            // 本地也修改过(在冲突窗口内) → 冲突
            if (localLocalModified > lastPullAt) {
                ConflictResolver.recordConflict(file, local)
            } else {
                kotlin.runCatching {
                    val bytes = file.download()
                    apply(String(bytes))
                }
            }
        }
    }

    // ============================ 应用远端数据 ============================

    private fun applyBook(cloudBook: Book, cloudModified: Long) {
        cloudBook.localModified = cloudModified
        cloudBook.cloudModified = cloudModified
        appDb.bookDao.insert(cloudBook)
        appDb.bookDao.markSynced(cloudBook.bookUrl, cloudModified)
    }

    private fun applyBookmark(cloudBookmark: Bookmark, cloudModified: Long) {
        cloudBookmark.localModified = cloudModified
        cloudBookmark.cloudModified = cloudModified
        appDb.bookmarkDao.insert(cloudBookmark)
        appDb.bookmarkDao.markSynced(cloudBookmark.time, cloudModified)
    }

    private fun applyBookGroup(cloudGroup: BookGroup, cloudModified: Long) {
        cloudGroup.localModified = cloudModified
        cloudGroup.cloudModified = cloudModified
        appDb.bookGroupDao.insert(cloudGroup)
        appDb.bookGroupDao.markSynced(cloudGroup.groupId, cloudModified)
    }

    private fun applyReadRecord(cloudRecord: ReadRecord, cloudModified: Long) {
        cloudRecord.localModified = cloudModified
        cloudRecord.cloudModified = cloudModified
        appDb.readRecordDao.insert(cloudRecord)
        appDb.readRecordDao.markSynced(
            cloudRecord.deviceId,
            cloudRecord.bookName,
            cloudModified
        )
    }

    /**
     * 应用远端删除: 先删除本地墓碑同键记录, 避免循环墓碑
     */
    private fun applyTombstone(tombstone: SyncTombstone) {
        // 避免循环墓碑: 先删除本地墓碑
        appDb.syncTombstoneDao.delete(tombstone.tableName, tombstone.recordKey)
        when (tombstone.tableName) {
            DataSyncType.BOOKS.tableName -> {
                appDb.bookDao.getBook(tombstone.recordKey)?.let {
                    appDb.bookDao.delete(it)
                }
            }

            DataSyncType.BOOKMARKS.tableName -> {
                tombstone.recordKey.toLongOrNull()?.let { time ->
                    appDb.bookmarkDao.getByTime(time)?.let {
                        appDb.bookmarkDao.delete(it)
                    }
                }
            }

            DataSyncType.BOOK_GROUPS.tableName -> {
                tombstone.recordKey.toLongOrNull()?.let { groupId ->
                    appDb.bookGroupDao.getByID(groupId)?.let {
                        appDb.bookGroupDao.delete(it)
                    }
                }
            }

            DataSyncType.READ_RECORDS.tableName -> {
                val (deviceId, bookName) = tombstone.recordKey.splitRecordKey()
                appDb.readRecordDao.get(deviceId, bookName)?.let {
                    appDb.readRecordDao.delete(it)
                }
            }
        }
    }

    // ============================ 设置文件 ============================

    suspend fun pushReadConfigs() {
        pushConfigFile(ReadBookConfig.configFileName, ReadBookConfig.configFilePath)
    }

    suspend fun pullReadConfigs() {
        pullConfigFile(ReadBookConfig.configFileName, ReadBookConfig.configFilePath)
    }

    private suspend fun pushConfigFile(fileName: String, localPath: String) {
        val file = java.io.File(localPath)
        if (!file.exists()) return
        val url = "${dirUrl(DataSyncType.READ_CONFIGS)}$fileName"
        webDav(url).upload(file.readBytes(), "application/json")
    }

    private suspend fun pullConfigFile(fileName: String, localPath: String) {
        val lastPullAt = SyncLedger.lastPullByType(DataSyncType.READ_CONFIGS)
        val url = "${dirUrl(DataSyncType.READ_CONFIGS)}$fileName"
        val file = webDav(url).getWebDavFile()
        if (file == null || file.lastModify <= lastPullAt) return
        val bytes = webDav(url).download()
        java.io.File(localPath).writeBytes(bytes)
        SyncLedger.setLastPullByType(DataSyncType.READ_CONFIGS, System.currentTimeMillis())
    }

    // ============================ 工具 ============================

    private suspend fun listRemoteFiles(url: String): List<WebDavFile> {
        return webDav(url).listFiles()
    }

    private suspend fun listRemoteFiles(type: DataSyncType): List<WebDavFile> {
        return listRemoteFiles(dirUrl(type))
    }

    private fun recordKeyOf(record: ReadRecord): String = "${record.deviceId}|${record.bookName}"

    private fun String.splitRecordKey(): Pair<String, String> {
        val index = indexOf('|')
        return if (index > 0) {
            substring(0, index) to substring(index + 1)
        } else {
            "" to this
        }
    }

    private fun tombstoneUrl(tombstone: SyncTombstone): String {
        return "${tombstonesUrl()}${tombstone.tableName}_${tombstone.recordKey.sha1Encode()}.json"
    }

    /** 用本地记录推算远端文件名对应的本地 key */
    private fun <T> WebDavFile.matchLocalKey(
        localList: List<T>,
        keyOf: (T) -> String
    ): String? {
        val sha = displayName.removeSuffix(".json")
        return localList.firstOrNull { keyOf(it).sha1Encode() == sha }?.let { keyOf(it) }
    }

    private fun Any.cloudModified(): Long = when (this) {
        is Book -> cloudModified
        is Bookmark -> cloudModified
        is BookGroup -> cloudModified
        is ReadRecord -> cloudModified
        else -> 0
    }

    private fun Any.localModified(): Long = when (this) {
        is Book -> localModified
        is Bookmark -> localModified
        is BookGroup -> localModified
        is ReadRecord -> localModified
        else -> 0
    }
}

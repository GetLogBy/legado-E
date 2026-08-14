package io.legado.app.help.sync

/**
 * 增量同步的数据类型
 */
enum class DataSyncType(val tableName: String, val dirName: String) {
    BOOKS("books", "books"),
    BOOKMARKS("bookmarks", "bookmarks"),
    BOOK_GROUPS("book_groups", "bookGroups"),
    READ_RECORDS("readRecord", "readRecords"),
    READ_CONFIGS("", "readConfigs");

    companion object {
        fun fromName(name: String): DataSyncType? {
            return values().firstOrNull { it.name == name }
        }
    }
}

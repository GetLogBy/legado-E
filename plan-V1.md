# legado-E 多端增量同步 — 实施规划 V1

**版本**：V1（规划稿）
**日期**：2026-08-14
**对应需求文档**：`需求文档.md`（阅读Sigma 增量同步）

---

## 0. 结论摘要

- **范围**：核心子集先行 —— 书架(books)、书签(bookmarks)、分组(book_groups)、阅读记录(readRecord)、阅读设置文件(ReadBookConfig/ThemeConfig)。
- **云端**：每条记录一个 JSON 文件，`PROPFIND` 的 `getlastmodified` 作为天然索引。
- **删除**：SQLite `AFTER DELETE` 触发器写墓碑表，推送墓碑文件。
- **调度**：新增 WorkManager 依赖做周期同步。

---

## 1. 总体架构

```
┌─ 触发源 ──────────────────────────────┐
│ App启动pull / 生命周期onStop push      │
│ WorkManager周期任务 / 手动"立即同步"    │
└──────────────┬────────────────────────┘
               ▼
      SyncManager (object, Mutex串行)
       ├─ ChangeCollector：扫描 dirty行 + 墓碑
       ├─ ConflictDetector：比对时间戳
       ├─ SyncClient：Push/Pull 记录及文件
       └─ SyncLedger：状态记录(LocalConfig共享参数)
               │
       WebDav库(复用 AppWebDav 鉴权)
               ▼
   legado/sync/books|bookmarks|bookGroups|readConfigs|readRecords|settings|tombstones/
```

## 2. 数据层改造（Room 89 → 90）

新增 `migration_89_90`（手动 Migration，`DatabaseMigrations.kt`）：

```sql
ALTER TABLE books        ADD local_modified INTEGER NOT NULL DEFAULT 0
ALTER TABLE books        ADD cloud_modified INTEGER NOT NULL DEFAULT 0
ALTER TABLE bookmarks    ADD local_modified INTEGER NOT NULL DEFAULT 0
ALTER TABLE bookmarks    ADD cloud_modified INTEGER NOT NULL DEFAULT 0
ALTER TABLE book_groups  ADD local_modified INTEGER NOT NULL DEFAULT 0
ALTER TABLE book_groups  ADD cloud_modified INTEGER NOT NULL DEFAULT 0
ALTER TABLE readRecord   ADD local_modified INTEGER NOT NULL DEFAULT 0
ALTER TABLE readRecord   ADD cloud_modified INTEGER NOT NULL DEFAULT 0

CREATE TABLE sync_tombstones (
  tableName TEXT NOT NULL, recordKey TEXT NOT NULL,
  deletedAt INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(tableName, recordKey))
```

实体类同步加字段（`Book.kt`、`Bookmark.kt`、`BookGroup.kt`、`ReadRecord.kt`，`@ColumnInfo(defaultValue="0")`）。`AppDatabase.kt` 版本号 `89→90`，`migrations` 数组加入 `migration_89_90`；旧 `backup.zip` 恢复路径不变（旧 JSON 无该字段，GSON 默认填 0）。

**全局同步台账**：不建 Room 表，用 `LocalConfig`（SharedPreferences）存 `lastPullByType`、`lastPushTime`——与现有 `LocalConfig.lastBackup` 模式一致、无额外 DAO。`sync_metadata` 表作为备选方案写入计划备注。

## 3. 变更检测（触发器）

在 `migration_89_90` 中为四张表创建触发器，`local_modified` 条件式打点，天然兼容"同步写入"与"用户写入"：

```sql
CREATE TRIGGER trg_books_up AFTER UPDATE ON books
WHEN NEW.local_modified = OLD.local_modified
BEGIN
  UPDATE books SET local_modified = NEW.modified_now  -- 即当前毫秒
  WHERE bookUrl = NEW.bookUrl;
END;
```

- **用户写入**：DAO 写入时 `local_modified` 保持 0/原值 → 触发器打 `now`。
- **同步拉取**：SyncClient 应用云端数据后用 `markSynced(key, cloudModified)` 一次性回写 `local_modified = cloud_modified`（≠旧值，触发器跳过，不会回环）。
- **删除**：`AFTER DELETE` 触发器把 `(tableName, recordKey)` 写入 `sync_tombstones`；同步引擎应用远端删除前先删除本地墓碑同键记录，避免循环墓碑。
- INSERT 同理（`AFTER INSERT ... WHEN NEW.local_modified = 0`）。

> 若实现期发现触发器 `WHEN` 条件跨表取 `now` 有局限，退化为"在关键写入点显式盖章"，实施时用 grep 定位所有 `bookDao.delete/insert/update` 与 `bookmarkDao.*` 调用点（已确认约 12~15 处，如 `BookmarkDialog.kt:63,71`、`BookshelfManageViewModel.kt:57`、`Book.kt:439`）。

## 4. 云端布局（每条记录一个文件）

`legado/<webDavDir>/sync/` 下按类型分子目录，文件名取自稳定主键（`sha1(bookUrl)`、bookmark 的 `time` 主键等）：

```
sync/books/<sha1(bookUrl)>.json      { modified, data:{...Book} }
sync/bookmarks/<time>.json
sync/bookGroups/<id>.json
sync/readRecords/<sha1(deviceId+bookName)>.json
sync/readConfigs/<configFileName>.json     # ReadBookConfig/ThemeConfig 整文件
sync/tombstones/<table>_<key>.json         { deleted:true, deletedAt }
```

**索引即目录**：`WebDav.listFiles()` 已返回 `lastModify`（`WebDav.kt:134`），直接作为每记录的云端时间戳，规避多端并发写单索引文件的丢更新问题。

- 推：PUT 变更记录文件 + 墓碑文件。
- 拉：PROPFIND 目录 → 对比本地 `cloud_modified` → 只下载增量 → `markSynced`。
- 墓碑 GC：上传成功且超过 N 天（默认 7 天，可配）后由创建端删除文件。

## 5. SyncManager 引擎

新建 `app/src/main/java/io/legado/app/help/sync/`：

- `SyncManager.kt`：Mutex 串行，入口 `syncNow(push=true, pull=true)`、`pushOnly()`、`pullOnly()`；复用 `AppWebDav.authorization`。
- `SyncConfig.kt`：读 SharedPreferences（enabled、interval、数据类型开关、默认冲突策略）。
- `SyncLedger.kt`：读写 `LocalConfig` 台账 + 状态（上次同步时间/结果，供 UI）。
- `SyncClient.kt`：按类型 push/pull，含失败重试与 `AppLog` 日志。
- `ConflictResolver.kt`：冲突检测/暂存/三种解法及默认策略。
- `SyncTypes.kt`：`DataSyncType` 枚举（books/bookmarks/groups/readRecords/settings）。

**冲突判定**：同一 recordKey，`local_modified > lastPullAt && cloudModified > lastPullAt` → 入 `conflicts` 表（Room 新表 + DAO，`localJson/cloudJson/时间戳/status`）。

## 6. 调度与生命周期

- 新增 `androidx.work:work-runtime-ktx` 到 `gradle/libs.versions.toml` 与 `app/build.gradle`。
- `sync/worker/SyncWorker.kt`：`CoroutineWorker`，`PeriodicWorkRequest`（最短 15min，用户间隔取 >=15min 档位）+ `NetworkType.CONNECTED` 约束；设置变更时重排。
- `App.kt:123` 处同步逻辑改为 `SyncManager.pullOnly()`（替换/并列现有 `downloadAllBookProgress`）。
- 进程退出推送：用 `ProcessLifecycleOwner` observer 或新建 `SyncLifecycleObserver` 在 `onStop` 触发 `pushOnly()`。
- 手动同步按钮调用 `syncNow()`。

## 7. UI

- **设置页**：新建 `res/xml/pref_config_sync.xml` + `SyncConfigFragment.kt`：总开关、同步间隔、数据类型多选、默认冲突策略、立即同步、上次同步时间、冲突列表入口。接入 `ConfigActivity` 各 Fragment 的挂载方式（参照 `BackupConfigFragment`）。
- **冲突解决**：新建 `ui/conflict/ConflictActivity.kt` + `ConflictViewModel.kt`，逐条字段 diff 展示，支持"忽略本地/忽略云端/保留两者"与"全部应用"，resolve 后经 `SyncClient` 回写。
- **状态提示**：书架顶部或设置行显示"上次同步 xx:xx / 失败"，用 `LiveEventBus` 广播 + `AppLogDialog` 查看日志。

## 8. 兼容与安全

- 密码/账号沿用 `PreferKey.webDav*` 现有加密存储（`BackupAES`），HTTPS 走现有 OkHttp。
- 旧 `backup.zip` 云文件保留，不做删除，仅新增 `sync/` 目录，互不干扰。
- minSdk 21 保持不变；WorkManager 支持 21+。

## 9. 里程碑与文件映射（对应需求文档 §9）

| 阶段 | 内容 | 关键改动 |
|---|---|---|
| M1 迁移与数据层 | DB 89→90、字段、触发器、tombstone 表、conflicts 表、DAO | `DatabaseMigrations.kt`、`Book/Bookmark/BookGroup/ReadRecord.kt`、`AppDatabase.kt`、新建 `sync` 相关 entity/DAO |
| M2 引擎核心 | SyncManager、ChangeCollector、SyncClient、冲突暂存 | `help/sync/*`，依赖 `work` |
| M3 调度 | WorkManager Worker、App/生命周期钩子、替换启动同步 | `SyncWorker.kt`、`App.kt`、`SyncLifecycleObserver` |
| M4 UI | 设置页、冲突页、状态提示 | `pref_config_sync.xml`、`SyncConfigFragment`、`ConflictActivity`、字符串资源 |
| M5 测试 | 冲突算法单测、迁移 androidTest、双模拟器同步演练 | `src/test`、`src/androidTest`（schema 已在 `app/schemas`） |
| M6 发布 | beta 验证 → 合并主分支 | 更新日志 |

## 10. 风险与开放项

- **删除语义改造**：现有 `Delete` 由触发器转墓碑，需验证 WebDAV/API 删除、`downloadAllBookProgress` 等旧路径不回环。
- **大书架性能**：PROPFIND 每类型一次；优先文件级增量。书籍封面不入同步（仅元数据）。
- **并发写冲突**：同一记录两端同时改 → 依赖冲突 UI；同一键递归冲突在 UI 端处理。
- 需求文档 §8 的三问：不同步书籍文件本身；冲突窗口以 `lastPullAt` 为界；提供"覆盖云端/清空云端"入口放入 M4 或后续迭代。

## 11.当前进度

### 已完成
M1 — 数据层改造（DB 89→90）
- 实体加 local_modified/cloud_modified：Book、Bookmark、BookGroup、ReadRecord
- 新增墓碑表 SyncTombstone + DAO、冲突表 SyncConflict + DAO
- DatabaseMigrations.kt 新增 migration_89_90（加列、建表、DELETE→墓碑触发器、本地修改时间触发器）
- 4 个 DAO 新增 needPush + markSynced，BookDao/BookmarkDao/ReadRecordDao 补 get-by-key
- AppDatabase 升 v90、注册新 DAO，onOpen 也建触发器（全新安装可用）
- 修复触发器 bug：up_modified 改为 NEW.cloud_modified = OLD.cloud_modified 条件，避免 markSynced 等值回写引发循环打点/重复推送
M2 — 同步引擎核心
- SyncTypes.kt：DataSyncType 枚举（books/bookmarks/bookGroups/readRecords/readConfigs）
- SyncConfig.kt：读取开关/周期/类型/冲突策略
- SyncLedger.kt：lastPullAt/lastPushTime/每类型游标（存 LocalConfig）
- SyncClient.kt：按类型的 push/pull、墓碑 push/pull、readConfigs 整文件同步、冲突窗口判断
- SyncManager.kt：Mutex 串行调度，syncNow/syncOnStart/syncOnStop，新增 suspend syncWorker 供 Worker 调用
- ConflictResolver.kt：冲突入表、manual/keepLocal/keepCloud 策略、自动解决
- 配置接入：PreferKey 新增 5 个 key，AppConfig 新增对应属性
- MD5Utils.sha1Encode、AppWebDav.syncRootUrl
M3 — 调度
- WorkManager 依赖已加（gradle/libs.versions.toml work=2.10.0、app/build.gradle work-runtime-ktx + lifecycle-process）
- SyncWorker.kt：CoroutineWorker，调 SyncManager.syncWorker()，失败返回 retry
- WorkManagerHelper.kt：PeriodicWorkRequest，间隔>=15min，NetworkType.CONNECTED 约束，UPDATE 策略重排/取消
- SyncLifecycleObserver.kt：ProcessLifecycleOwner ON_STOP → SyncManager.syncOnStop()
- App.kt：onCreate 调度周期任务、注册生命周期观察者、启动后 syncOnStart()
- AppConfig：syncEnabled/syncInterval 变更时重排周期任务
- 注：本机无 Android SDK，未编译验证，需在本地跑 `./gradlew :app:compileAppDebugKotlin` 确认
M4 — UI/设置
- pref_config_sync.xml 设置页（总开关/同步间隔/数据类型多选/默认冲突策略/立即同步/上次同步状态/冲突列表入口）
- MultiSelectPreference.kt：自定义多选偏好（右侧标签显示选中项），与 NameListPreference 同款外观
- SyncConfigFragment.kt：首选写入默认数据类型、立即同步（WaitDialog + SyncManager.syncNow）、状态摘要刷新
- AppConfig.syncDataTypes 改为 StringSet 存取（匹配 MultiSelectListPreference），syncInterval 改读 String（避免 ListPreference 存字符串被 getPrefInt 解析崩溃）
- ConfigTag.SYNC_CONFIG + ConfigActivity 挂载 + pref_main.xml/MyFragment 增加"增量同步"入口
- ConflictActivity + ConflictViewModel：冲突列表（类型/记录键 + 保留本地/保留云端/保留两者），已解决置灰，菜单支持"全部应用本地/云端"与"清除已解决"
- ConflictResolver 新增 keepBoth()（两端各留一份、消除冲突）
- SyncConflictDao 新增 observeAll()/pendingCount
- 字符串/数组资源、AndroidManifest 注册 ConflictActivity

### 未完成
M5 — 测试
- 冲突算法单测、迁移 androidTest、双模拟器同步演练未做
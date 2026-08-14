package io.legado.app.ui.conflict

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.SyncConflict
import io.legado.app.help.sync.ConflictResolver

/**
 * 同步冲突解决页 ViewModel
 */
class ConflictViewModel(application: Application) : BaseViewModel(application) {

    val conflicts = appDb.syncConflictDao.observeAll()

    fun keepLocal(conflict: SyncConflict) = execute {
        ConflictResolver.keepLocal(conflict)
    }

    fun keepCloud(conflict: SyncConflict) = execute {
        ConflictResolver.keepCloud(conflict)
    }

    fun keepBoth(conflict: SyncConflict) = execute {
        ConflictResolver.keepBoth(conflict)
    }

    /** 对所有待处理冲突应用默认保留本地策略 */
    fun applyAllLocal() = execute {
        appDb.syncConflictDao.pending().forEach {
            ConflictResolver.keepLocal(it)
        }
    }

    /** 对所有待处理冲突应用默认保留云端策略 */
    fun applyAllCloud() = execute {
        appDb.syncConflictDao.pending().forEach {
            ConflictResolver.keepCloud(it)
        }
    }

    fun clearResolved() = execute {
        appDb.syncConflictDao.clearResolved()
    }

}
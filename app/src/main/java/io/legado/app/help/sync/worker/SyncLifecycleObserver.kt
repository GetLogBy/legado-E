package io.legado.app.help.sync.worker

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import io.legado.app.help.sync.SyncManager

/**
 * 进程退到后台时推送本地变更
 */
class SyncLifecycleObserver : LifecycleObserver {

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppStop() {
        SyncManager.syncOnStop()
    }
}
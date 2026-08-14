package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.sync.SyncLedger
import io.legado.app.help.sync.SyncManager
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.conflict.ConflictActivity
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.putPrefStringSet
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

/**
 * 增量同步设置页
 */
class SyncConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val waitDialog by lazy { WaitDialog(requireContext()) }

    private val syncNowKey = "sync_now"
    private val syncConflictsKey = "sync_conflicts"
    private val syncLastStatusKey = "sync_last_status"

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_sync)
        initDataTypesDefault()
        upLastStatus()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.sync_setting)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onResume() {
        super.onResume()
        upLastStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            syncNowKey -> syncNow()
            syncConflictsKey -> startActivity<ConflictActivity>()
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.syncDataTypes -> listView.post {
                // 重新绑定偏好项, 刷新多选右侧选中标签
                listView.adapter?.notifyDataSetChanged()
            }
        }
    }

    /**
     * 首次进入时写入默认数据类型, 保证多选列表有初始选中项
     */
    private fun initDataTypesDefault() {
        val prefs = preferenceManager.sharedPreferences ?: return
        if (!prefs.contains(PreferKey.syncDataTypes)) {
            putPrefStringSet(
                PreferKey.syncDataTypes,
                mutableSetOf("books", "bookmarks", "bookGroups", "readRecords")
            )
        }
    }

    private fun syncNow() {
        if (!AppConfig.syncEnabled) {
            toastOnUi(R.string.sync_enable_tip)
            return
        }
        waitDialog.setText(R.string.syncing)
        waitDialog.show()
        SyncManager.syncNow {
            waitDialog.dismiss()
            listView.post {
                upLastStatus()
            }
        }
    }

    private fun upLastStatus() {
        val preference = findPreference<Preference>(syncLastStatusKey) ?: return
        preference.summary = SyncLedger.lastResult ?: getString(R.string.sync_last_status_s)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        waitDialog.dismiss()
    }

}

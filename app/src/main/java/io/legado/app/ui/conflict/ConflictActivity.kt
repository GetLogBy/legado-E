package io.legado.app.ui.conflict

import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SyncConflict
import io.legado.app.databinding.ActivitySyncConflictBinding
import io.legado.app.databinding.ItemSyncConflictBinding
import io.legado.app.help.sync.DataSyncType
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * 同步冲突列表页, 逐条解决冲突
 */
class ConflictActivity : VMBaseActivity<ActivitySyncConflictBinding, ConflictViewModel>() {

    override val binding by viewBinding(ActivitySyncConflictBinding::inflate)
    override val viewModel by viewModels<ConflictViewModel>()

    private val adapter by lazy { ConflictAdapter(this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        lifecycleScope.launch {
            viewModel.conflicts.collect { list ->
                adapter.setItems(list)
                binding.tvEmpty.isVisible = list.isEmpty()
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_sync_conflict, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_apply_all_local -> viewModel.applyAllLocal()
            R.id.menu_apply_all_cloud -> viewModel.applyAllCloud()
            R.id.menu_clear_resolved -> viewModel.clearResolved()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    inner class ConflictAdapter(context: Context) :
        RecyclerAdapter<SyncConflict, ItemSyncConflictBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemSyncConflictBinding {
            return ItemSyncConflictBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemSyncConflictBinding,
            item: SyncConflict,
            payloads: MutableList<Any>,
        ) {
            binding.apply {
                tvType.text = typeName(item.tableName)
                tvKey.text = getString(R.string.conflict_record_key, item.recordKey)
                tvStatus.isVisible = item.status == 1
                resolveGroup.isVisible = item.status == 0
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemSyncConflictBinding) {
            binding.apply {
                btnKeepLocal.setOnClickListener {
                    getItem(holder.layoutPosition)?.let { viewModel.keepLocal(it) }
                }
                btnKeepCloud.setOnClickListener {
                    getItem(holder.layoutPosition)?.let { viewModel.keepCloud(it) }
                }
                btnKeepBoth.setOnClickListener {
                    getItem(holder.layoutPosition)?.let { viewModel.keepBoth(it) }
                }
            }
        }

        private fun typeName(tableName: String): String = when (tableName) {
            DataSyncType.BOOKS.tableName -> getString(R.string.sync_type_books)
            DataSyncType.BOOKMARKS.tableName -> getString(R.string.sync_type_bookmarks)
            DataSyncType.BOOK_GROUPS.tableName -> getString(R.string.sync_type_bookgroups)
            DataSyncType.READ_RECORDS.tableName -> getString(R.string.sync_type_readrecords)
            else -> tableName
        }
    }

}
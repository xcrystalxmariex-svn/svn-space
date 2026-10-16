package space.svn.svnspace.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import space.svn.svnspace.data.model.WatchedFolder
import space.svn.svnspace.databinding.ItemWatchedFolderBinding

class WatchedFolderAdapter(
    private val onToggle: (WatchedFolder, Boolean) -> Unit,
    private val onDelete: (WatchedFolder) -> Unit
) : ListAdapter<WatchedFolder, WatchedFolderAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemWatchedFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemWatchedFolderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WatchedFolder) {
            binding.txtPath.text = item.displayPath
            binding.switchEnabled.isChecked = item.isEnabled
            binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
                onToggle(item, checked)
            }
            binding.btnDelete.setOnClickListener { onDelete(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<WatchedFolder>() {
            override fun areItemsTheSame(a: WatchedFolder, b: WatchedFolder) = a.id == b.id
            override fun areContentsTheSame(a: WatchedFolder, b: WatchedFolder) = a == b
        }
    }
}

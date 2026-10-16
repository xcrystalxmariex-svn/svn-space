package space.svn.svnspace.ui.tree

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import space.svn.svnspace.databinding.ItemTreeNodeBinding

/**
 * Simple expandable tree adapter.
 * Nodes can be folders (expandable) or files.
 */
class FileTreeAdapter(
    private val onClick: (TreeNode) -> Unit,
    private val onLongClick: (TreeNode) -> Unit = {}
) : RecyclerView.Adapter<FileTreeAdapter.VH>() {

    private val visible = mutableListOf<TreeNode>()

    fun setRoots(roots: List<TreeNode>) {
        visible.clear()
        visible.addAll(roots)
        notifyDataSetChanged()
    }

    fun expand(node: TreeNode, children: List<TreeNode>) {
        val idx = visible.indexOf(node)
        if (idx < 0) return
        node.isExpanded = true
        node.children = children
        visible.addAll(idx + 1, children)
        notifyItemChanged(idx)
        notifyItemRangeInserted(idx + 1, children.size)
    }

    fun collapse(node: TreeNode) {
        val idx = visible.indexOf(node)
        if (idx < 0 || !node.isExpanded) return
        val removeCount = countDescendants(node)
        node.isExpanded = false
        if (removeCount > 0) {
            visible.subList(idx + 1, idx + 1 + removeCount).clear()
            notifyItemRangeRemoved(idx + 1, removeCount)
        }
        notifyItemChanged(idx)
    }

    private fun countDescendants(node: TreeNode): Int {
        if (!node.isExpanded || node.children.isEmpty()) return 0
        var count = node.children.size
        for (c in node.children) count += countDescendants(c)
        return count
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTreeNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(visible[position])
    }

    override fun getItemCount() = visible.size

    inner class VH(private val binding: ItemTreeNodeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(node: TreeNode) {
            binding.txtName.text = node.name
            binding.txtMeta.text = if (node.isDirectory) "Folder" else formatSize(node.size)
            val pad = (node.depth * 24)
            binding.root.setPadding(pad + 16, 12, 16, 12)

            binding.imgIcon.setImageResource(
                if (node.isDirectory) android.R.drawable.ic_menu_agenda
                else android.R.drawable.ic_menu_save
            )
            binding.imgExpand.visibility = if (node.isDirectory) View.VISIBLE else View.INVISIBLE
            binding.imgExpand.rotation = if (node.isExpanded) 90f else 0f

            binding.root.setOnClickListener {
                if (node.isDirectory) {
                    if (node.isExpanded) collapse(node) else onClick(node)
                } else {
                    onClick(node)
                }
            }
            binding.root.setOnLongClickListener {
                onLongClick(node)
                true
            }
        }

        private fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
            return "%.1f MB".format(bytes / (1024.0 * 1024))
        }
    }
}

data class TreeNode(
    val id: String,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val depth: Int = 0,
    val connectionId: String? = null,   // null = local
    var isExpanded: Boolean = false,
    var children: List<TreeNode> = emptyList()
)

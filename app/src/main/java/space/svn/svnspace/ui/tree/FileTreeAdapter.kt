package space.svn.svnspace.ui.tree

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import space.svn.svnspace.databinding.ItemTreeNodeBinding

/**
 * Simple expandable tree adapter.
 * Nodes can be folders (expandable), files, section headers, messages or actions.
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
        val idx = indexOfNode(node)
        if (idx < 0) return
        if (node.isExpanded) collapse(node)
        node.isExpanded = true
        node.children = children
        visible.addAll(idx + 1, children)
        notifyItemChanged(idx)
        notifyItemRangeInserted(idx + 1, children.size)
    }

    fun collapse(node: TreeNode) {
        val idx = indexOfNode(node)
        if (idx < 0 || !node.isExpanded) return
        val removeCount = countDescendants(node)
        node.isExpanded = false
        if (removeCount > 0) {
            visible.subList(idx + 1, idx + 1 + removeCount).clear()
            notifyItemRangeRemoved(idx + 1, removeCount)
        }
        notifyItemChanged(idx)
    }

    /** Identity lookup: two different folders can share a name and path text. */
    private fun indexOfNode(node: TreeNode): Int = visible.indexOfFirst { it === node }

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
            binding.txtName.setTypeface(null, if (node.kind == TreeNode.Kind.HEADER) Typeface.BOLD else Typeface.NORMAL)
            binding.txtName.alpha = if (node.kind == TreeNode.Kind.MESSAGE) 0.7f else 1f
            val meta = node.meta ?: when (node.kind) {
                TreeNode.Kind.ITEM -> if (node.isDirectory) "Folder" else formatSize(node.size)
                else -> null
            }
            binding.txtMeta.text = meta.orEmpty()
            binding.txtMeta.visibility = if (meta.isNullOrBlank()) View.GONE else View.VISIBLE
            val dp = binding.root.resources.displayMetrics.density
            val pad = (node.depth * 20 * dp).toInt()
            binding.root.setPadding(pad + (16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())

            when (node.kind) {
                TreeNode.Kind.ITEM -> {
                    binding.imgIcon.visibility = View.VISIBLE
                    binding.imgIcon.setImageResource(
                        when {
                            node.depth == 0 && node.connectionId != null -> android.R.drawable.ic_menu_upload
                            node.isDirectory -> android.R.drawable.ic_menu_agenda
                            else -> android.R.drawable.ic_menu_save
                        }
                    )
                    binding.imgExpand.visibility = if (node.isDirectory) View.VISIBLE else View.INVISIBLE
                    binding.imgExpand.rotation = if (node.isExpanded) 90f else 0f
                }
                TreeNode.Kind.ACTION -> {
                    binding.imgIcon.visibility = View.VISIBLE
                    binding.imgIcon.setImageResource(android.R.drawable.ic_input_add)
                    binding.imgExpand.visibility = View.INVISIBLE
                }
                TreeNode.Kind.HEADER, TreeNode.Kind.MESSAGE -> {
                    binding.imgIcon.visibility = View.GONE
                    binding.imgExpand.visibility = if (node.kind == TreeNode.Kind.HEADER) View.GONE else View.INVISIBLE
                }
            }

            binding.root.setOnClickListener {
                when (node.kind) {
                    TreeNode.Kind.ITEM ->
                        if (node.isDirectory && node.isExpanded) collapse(node) else onClick(node)
                    TreeNode.Kind.ACTION -> onClick(node)
                    else -> {}
                }
            }
            binding.root.setOnLongClickListener {
                if (node.kind == TreeNode.Kind.ITEM) onLongClick(node)
                true
            }
        }

        private fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
            if (bytes < 1024L * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024))
            return "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}

/** A row in the Files tree. Plain class: rows are matched by identity, not by value. */
class TreeNode(
    val id: String,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val depth: Int = 0,
    val connectionId: String? = null,   // null = local
    var isExpanded: Boolean = false,
    var children: List<TreeNode> = emptyList(),
    val meta: String? = null,
    val kind: Kind = Kind.ITEM,
    val action: String? = null
) {
    enum class Kind { ITEM, HEADER, MESSAGE, ACTION }
}

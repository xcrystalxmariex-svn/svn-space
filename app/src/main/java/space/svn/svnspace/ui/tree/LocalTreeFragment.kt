package space.svn.svnspace.ui.tree

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.databinding.FragmentTreeBinding

class LocalTreeFragment : Fragment() {

    private var _binding: FragmentTreeBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: FileTreeAdapter
    private lateinit var repo: SvnRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTreeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = SvnRepository(requireContext())
        adapter = FileTreeAdapter(
            onClick = { node ->
                if (node.isDirectory && !node.isExpanded) loadChildren(node)
            },
            onLongClick = { node ->
                Toast.makeText(requireContext(), node.path, Toast.LENGTH_SHORT).show()
            }
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.swipe.setOnRefreshListener { loadRoots() }
        loadRoots()
    }

    private fun loadRoots() {
        lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) { repo.getEnabledWatchedFolders() }
            val roots = folders.map { f ->
                TreeNode(
                    id = f.id,
                    name = f.displayPath,
                    path = f.localUri,
                    isDirectory = true,
                    depth = 0
                )
            }
            adapter.setRoots(roots)
            binding.swipe.isRefreshing = false
            if (roots.isEmpty()) {
                binding.txtEmpty.visibility = View.VISIBLE
                binding.txtEmpty.text = "No watched folders yet.\nGo to Settings → Manage watched folders."
            } else {
                binding.txtEmpty.visibility = View.GONE
            }
        }
    }

    private fun loadChildren(node: TreeNode) {
        lifecycleScope.launch {
            val children = withContext(Dispatchers.IO) {
                val uri = Uri.parse(node.path)
                val doc = if (space.svn.svnspace.util.StorageAccess.isFileUri(node.path)) {
                    space.svn.svnspace.util.StorageAccess.rootOf(requireContext(), node.path)
                } else {
                    DocumentFile.fromTreeUri(requireContext(), uri)
                        ?: DocumentFile.fromSingleUri(requireContext(), uri)
                }
                doc?.listFiles()?.map { child ->
                    TreeNode(
                        id = child.uri.toString(),
                        name = child.name ?: "?",
                        path = child.uri.toString(),
                        isDirectory = child.isDirectory,
                        size = if (child.isFile) child.length() else 0,
                        depth = node.depth + 1
                    )
                } ?: emptyList()
            }
            adapter.expand(node, children)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

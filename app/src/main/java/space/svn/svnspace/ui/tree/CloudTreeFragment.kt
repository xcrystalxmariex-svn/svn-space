package space.svn.svnspace.ui.tree

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.remote.GoogleDriveClient
import space.svn.svnspace.data.remote.WebDavClient
import space.svn.svnspace.ui.connections.OAuthHelper
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.databinding.FragmentTreeBinding

class CloudTreeFragment : Fragment() {

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
                if (node.isDirectory && !node.isExpanded) {
                    loadRemoteChildren(node)
                } else if (!node.isDirectory) {
                    Toast.makeText(requireContext(), "File: ${node.name}", Toast.LENGTH_SHORT).show()
                }
            }
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.swipe.setOnRefreshListener { binding.swipe.isRefreshing = false }

        lifecycleScope.launch {
            repo.observeConnections().collectLatest { list ->
                val roots = list.filter { it.isEnabled }.map { c ->
                    TreeNode(
                        id = c.id,
                        name = c.displayName,
                        path = c.basePath ?: "/",
                        isDirectory = true,
                        depth = 0,
                        connectionId = c.id
                    )
                }
                adapter.setRoots(roots)
                binding.txtEmpty.visibility = if (roots.isEmpty()) View.VISIBLE else View.GONE
                binding.txtEmpty.text = "No connections yet.\nAdd one in Settings."
            }
        }
    }

    private fun loadRemoteChildren(node: TreeNode) {
        val connId = node.connectionId ?: return
        lifecycleScope.launch {
            try {
                val children = withContext(Dispatchers.IO) {
                    val conn = repo.getConnection(connId) ?: return@withContext emptyList()
                    val secret = repo.getSecret(connId) ?: return@withContext emptyList()
                    when (conn.type) {
                        ConnectionType.WEBDAV, ConnectionType.CUSTOM_HTTP -> {
                            val client = WebDavClient(
                                baseUrl = conn.host ?: return@withContext emptyList(),
                                username = conn.username ?: "",
                                password = secret,
                                useTls = conn.useTls
                            )
                            client.list(node.path).map { entry ->
                                TreeNode(
                                    id = entry.path,
                                    name = entry.name,
                                    path = entry.path,
                                    isDirectory = entry.isDirectory,
                                    depth = node.depth + 1,
                                    connectionId = connId
                                )
                            }
                        }
                        ConnectionType.OAUTH_GOOGLE -> {
                            val drive = GoogleDriveClient(OAuthHelper.GOOGLE_CLIENT_ID, secret)
                            drive.list(node.path).map { entry ->
                                TreeNode(
                                    id = entry.id,
                                    name = entry.name,
                                    path = entry.path,
                                    isDirectory = entry.isDirectory,
                                    size = entry.size,
                                    depth = node.depth + 1,
                                    connectionId = connId
                                )
                            }
                        }
                        else -> emptyList()
                    }
                }
                adapter.expand(node, children)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "List failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package space.svn.svnspace.ui.tree

import android.content.Intent
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.model.WatchedFolder
import space.svn.svnspace.data.remote.GoogleDriveClient
import space.svn.svnspace.data.remote.WebDavClient
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.databinding.FragmentTreeBinding
import space.svn.svnspace.ui.connections.AddConnectionActivity
import space.svn.svnspace.ui.connections.OAuthHelper
import space.svn.svnspace.ui.settings.WatchedFoldersActivity
import space.svn.svnspace.util.StorageAccess

/**
 * Files tab: your cloud providers (browsable) and the folders you watch on this device.
 *
 * Before, a provider expanded to nothing when its folder was empty or the listing failed,
 * and with no providers the list was blank. Now every expand shows its result – files,
 * "nothing backed up yet", or the reason it couldn't load – and there's always a way
 * to add a provider from here.
 */
class CloudTreeFragment : Fragment() {

    private var _binding: FragmentTreeBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: FileTreeAdapter
    private lateinit var repo: SvnRepository

    private var connections: List<Connection> = emptyList()
    private var folders: List<WatchedFolder> = emptyList()
    /** Local folder rows → their DocumentFile, so subfolders expand to themselves. */
    private val localDocs = HashMap<String, DocumentFile>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTreeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = SvnRepository(requireContext())
        adapter = FileTreeAdapter(onClick = { node -> onNodeClicked(node) })
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.swipe.setOnRefreshListener {
            render()
            binding.swipe.isRefreshing = false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeConnections()
                .combine(repo.observeWatchedFolders()) { c, f -> c to f }
                .collect { (c, f) ->
                    connections = c.filter { it.isEnabled }
                    folders = f
                    render()
                }
        }
    }

    private fun render() {
        val b = _binding ?: return
        localDocs.clear()
        val rows = mutableListOf<TreeNode>()
        rows.add(header("Cloud providers"))
        connections.forEach { c ->
            rows.add(
                TreeNode(
                    id = c.id,
                    name = c.displayName,
                    path = c.basePath ?: "/",
                    isDirectory = true,
                    depth = 0,
                    connectionId = c.id,
                    meta = "${providerLabel(c)} · ${c.basePath ?: "/"}"
                )
            )
        }
        if (connections.none { it.type == ConnectionType.OAUTH_GOOGLE }) {
            rows.add(action("Connect Google Drive", ACTION_GOOGLE))
        }
        rows.add(action("Add WebDAV or custom server", ACTION_ADD))

        rows.add(header("On this device"))
        if (folders.isEmpty()) {
            rows.add(message("No watched folders yet"))
        }
        folders.forEach { f ->
            rows.add(
                TreeNode(
                    id = f.id,
                    name = f.displayPath,
                    path = f.localUri,
                    isDirectory = true,
                    depth = 0,
                    meta = StorageAccess.problemWith(requireContext(), f.localUri)
                        ?: if (f.isEnabled) "Watched folder" else "Watched folder (off)"
                )
            )
        }
        rows.add(action("Add a watched folder", ACTION_FOLDERS))
        adapter.setRoots(rows)
        b.txtEmpty.visibility = View.GONE
    }

    private fun onNodeClicked(node: TreeNode) {
        when (node.kind) {
            TreeNode.Kind.ACTION -> when (node.action) {
                ACTION_GOOGLE -> OAuthHelper.launchGoogle(requireContext())
                ACTION_ADD -> startActivity(Intent(requireContext(), AddConnectionActivity::class.java))
                ACTION_FOLDERS -> startActivity(Intent(requireContext(), WatchedFoldersActivity::class.java))
            }
            TreeNode.Kind.ITEM -> when {
                !node.isDirectory -> Toast.makeText(requireContext(), node.name, Toast.LENGTH_SHORT).show()
                node.connectionId != null -> loadRemoteChildren(node)
                else -> loadLocalChildren(node)
            }
            else -> {}
        }
    }

    private fun loadLocalChildren(node: TreeNode) {
        val ctx = requireContext().applicationContext
        _binding?.swipe?.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val children = withContext(Dispatchers.IO) {
                try {
                    val problem = if (node.depth == 0) StorageAccess.problemWith(ctx, node.path) else null
                    if (problem != null) return@withContext listOf(message(problem, node.depth + 1))
                    val dir = localDocs[node.id] ?: StorageAccess.rootOf(ctx, node.path)
                        ?: return@withContext listOf(message("Can't open this folder", node.depth + 1))
                    val files = dir.listFiles()
                        .sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))
                    if (files.isEmpty()) return@withContext listOf(message("Empty folder", node.depth + 1))
                    files.map { child ->
                        val id = child.uri.toString()
                        if (child.isDirectory) localDocs[id] = child
                        TreeNode(
                            id = id,
                            name = child.name ?: "?",
                            path = id,
                            isDirectory = child.isDirectory,
                            size = if (child.isFile) child.length() else 0,
                            depth = node.depth + 1
                        )
                    }
                } catch (e: Exception) {
                    listOf(message("Couldn't read: ${e.message ?: e.javaClass.simpleName}", node.depth + 1))
                }
            }
            _binding?.swipe?.isRefreshing = false
            adapter.expand(node, children)
        }
    }

    private fun loadRemoteChildren(node: TreeNode) {
        val connId = node.connectionId ?: return
        _binding?.swipe?.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val children = withContext(Dispatchers.IO) {
                val depth = node.depth + 1
                try {
                    val conn = repo.getConnection(connId)
                        ?: return@withContext listOf(message("This connection was removed", depth))
                    val secret = repo.getSecret(connId)
                        ?: return@withContext listOf(message("Not signed in – connect this provider again", depth))
                    val list = when (conn.type) {
                        ConnectionType.WEBDAV, ConnectionType.CUSTOM_HTTP -> {
                            val host = conn.host
                                ?: return@withContext listOf(message("Server address missing", depth))
                            val client = WebDavClient(
                                baseUrl = host,
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
                                    depth = depth,
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
                                    depth = depth,
                                    connectionId = connId
                                )
                            }
                        }
                        else -> return@withContext listOf(message("Browsing this provider isn't supported yet", depth))
                    }
                    list.ifEmpty {
                        listOf(
                            message(
                                if (node.depth == 0) "Nothing backed up here yet – tap Scan Now on Home"
                                else "Empty folder",
                                depth
                            )
                        )
                    }
                } catch (e: GoogleDriveClient.ReauthRequiredException) {
                    listOf(message("Google sign-in expired – connect it again", depth))
                } catch (e: Exception) {
                    listOf(message("Couldn't load: ${e.message ?: e.javaClass.simpleName}", depth))
                }
            }
            _binding?.swipe?.isRefreshing = false
            adapter.expand(node, children)
        }
    }

    private fun providerLabel(c: Connection): String = when (c.type) {
        ConnectionType.OAUTH_GOOGLE -> "Google Drive"
        ConnectionType.OAUTH_MICROSOFT -> "OneDrive"
        ConnectionType.OAUTH_DROPBOX -> "Dropbox"
        ConnectionType.WEBDAV -> "WebDAV"
        else -> "Custom"
    }

    private fun header(text: String) =
        TreeNode(id = "h:$text", name = text, path = "", isDirectory = false, kind = TreeNode.Kind.HEADER)

    private fun message(text: String, depth: Int = 0) =
        TreeNode(id = "m:$text", name = text, path = "", isDirectory = false, depth = depth, kind = TreeNode.Kind.MESSAGE)

    private fun action(text: String, action: String) =
        TreeNode(id = "a:$action", name = text, path = "", isDirectory = false, kind = TreeNode.Kind.ACTION, action = action)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ACTION_GOOGLE = "google"
        private const val ACTION_ADD = "add"
        private const val ACTION_FOLDERS = "folders"
    }
}

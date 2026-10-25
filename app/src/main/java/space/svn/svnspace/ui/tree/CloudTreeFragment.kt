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
import space.svn.svnspace.ui.browser.CloudBrowserActivity
import space.svn.svnspace.ui.connections.OAuthHelper
import space.svn.svnspace.ui.connections.Reconnect
import space.svn.svnspace.util.AuthState
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
            // Providers open at their real top level (My Drive, or the server's root), not at
            // the app's own backup folder, so files that were already there show up too.
            val isDrive = c.type == ConnectionType.OAUTH_GOOGLE
            val reason = AuthState.reasonFor(requireContext(), c.id)
            rows.add(
                TreeNode(
                    id = c.id,
                    name = c.displayName,
                    path = if (isDrive) DRIVE_ROOT else "/",
                    isDirectory = true,
                    depth = 0,
                    connectionId = c.id,
                    meta = reason?.let { AuthState.label(it) }
                        ?: if (isDrive) "Google Drive · My Drive" else "${providerLabel(c)} · all folders"
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
                else -> {
                    val a = node.action.orEmpty()
                    when {
                        a.startsWith(ACTION_RECONNECT) -> reconnect(a.removePrefix(ACTION_RECONNECT))
                        a.startsWith(ACTION_BROWSE) -> startActivity(
                            CloudBrowserActivity.intent(requireContext(), a.removePrefix(ACTION_BROWSE))
                        )
                    }
                }
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
        val ctx = requireContext().applicationContext
        _binding?.swipe?.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val children = withContext(Dispatchers.IO) {
                val depth = node.depth + 1
                try {
                    val conn = repo.getConnection(connId)
                        ?: return@withContext listOf(message("This connection was removed", depth))
                    val secret = repo.getSecret(connId)
                        ?: return@withContext listOf(
                            action("Not signed in – tap to connect it again", ACTION_RECONNECT + connId, depth)
                        )
                    when (conn.type) {
                        ConnectionType.WEBDAV, ConnectionType.CUSTOM_HTTP -> {
                            val host = conn.host
                                ?: return@withContext listOf(message("Server address missing", depth))
                            val client = WebDavClient(
                                baseUrl = host,
                                username = conn.username ?: "",
                                password = secret,
                                useTls = conn.useTls
                            )
                            val list = client.list(node.path)
                                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                                .map { entry ->
                                    TreeNode(
                                        id = "$connId:${entry.path}",
                                        name = entry.name,
                                        path = entry.path,
                                        isDirectory = entry.isDirectory,
                                        size = entry.size,
                                        depth = depth,
                                        connectionId = connId
                                    )
                                }
                            val rows = mutableListOf<TreeNode>()
                            if (node.depth == 0) rows.add(action("Open in file browser", ACTION_BROWSE + connId, depth))
                            rows.addAll(list.ifEmpty { listOf(message("Empty folder", depth)) })
                            rows
                        }
                        ConnectionType.OAUTH_GOOGLE -> driveChildren(ctx, node, connId, secret, depth)
                        else -> listOf(message("Browsing this provider isn't supported yet", depth))
                    }
                } catch (e: GoogleDriveClient.ScopeUpgradeRequiredException) {
                    AuthState.markNeedsReconnect(ctx, connId, AuthState.Reason.LIMITED_SCOPE)
                    listOf(action("Limited Google access – tap to reconnect", ACTION_RECONNECT + connId, depth))
                } catch (e: GoogleDriveClient.ReauthRequiredException) {
                    AuthState.markNeedsReconnect(ctx, connId, AuthState.Reason.EXPIRED)
                    listOf(action("Google sign-in expired – tap to reconnect", ACTION_RECONNECT + connId, depth))
                } catch (e: Exception) {
                    listOf(message("Couldn't load: ${e.message ?: e.javaClass.simpleName}", depth))
                }
            }
            _binding?.swipe?.isRefreshing = false
            adapter.expand(node, children)
        }
    }

    /**
     * Google Drive rows, listed by folder ID like the Drive app: the provider row shows the
     * top of My Drive plus "Shared with me". Before, it listed the app's /svn-space folder
     * only, so files that were already in the account never showed.
     */
    private fun driveChildren(
        ctx: android.content.Context,
        node: TreeNode,
        connId: String,
        secret: String,
        depth: Int
    ): List<TreeNode> {
        val drive = GoogleDriveClient(OAuthHelper.GOOGLE_CLIENT_ID, secret)
        val rows = mutableListOf<TreeNode>()
        if (node.depth == 0) {
            // An old login from before the full-Drive change can only see files this app
            // made, and Google returns that smaller list without any error. Say so.
            when (drive.grantedFullDrive()) {
                false -> {
                    AuthState.markNeedsReconnect(ctx, connId, AuthState.Reason.LIMITED_SCOPE)
                    rows.add(
                        action(
                            "Limited access: only files svn-space made are shown. Tap to reconnect",
                            ACTION_RECONNECT + connId, depth
                        )
                    )
                }
                true -> if (AuthState.reasonFor(ctx, connId) == AuthState.Reason.LIMITED_SCOPE) {
                    AuthState.clear(ctx, connId)
                }
                null -> {}
            }
            rows.add(action("Open in file browser", ACTION_BROWSE + connId, depth))
        }
        val items = when {
            node.path == DRIVE_SHARED -> drive.listSharedWithMe().items
            node.path.startsWith(ID_PREFIX) -> drive.listAllChildren(node.path.removePrefix(ID_PREFIX))
            else -> drive.listAllChildren("root")
        }
        items.forEach { item ->
            val folderId = item.targetId ?: item.id
            rows.add(
                TreeNode(
                    id = "$connId:${item.id}:${node.id}",
                    name = item.name,
                    path = ID_PREFIX + folderId,
                    isDirectory = item.isFolder,
                    size = item.size,
                    depth = depth,
                    connectionId = connId,
                    meta = when {
                        item.isFolder -> if (item.isShortcut) "Shortcut to folder" else null
                        item.isGoogleType -> "Google file"
                        else -> null
                    }
                )
            )
        }
        if (items.isEmpty()) rows.add(message("Empty folder", depth))
        if (node.depth == 0) {
            rows.add(
                TreeNode(
                    id = "$connId:shared",
                    name = "Shared with me",
                    path = DRIVE_SHARED,
                    isDirectory = true,
                    depth = depth,
                    connectionId = connId
                )
            )
        }
        return rows
    }

    private fun reconnect(connId: String) {
        val activity = activity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val conn = withContext(Dispatchers.IO) { repo.getConnection(connId) } ?: return@launch
            Reconnect.start(activity, viewLifecycleOwner.lifecycleScope, repo, conn) { render() }
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

    private fun action(text: String, action: String, depth: Int = 0) =
        TreeNode(id = "a:$action", name = text, path = "", isDirectory = false, depth = depth, kind = TreeNode.Kind.ACTION, action = action)

    override fun onResume() {
        super.onResume()
        // Coming back from a reconnect: refresh the rows and their status labels.
        if (_binding != null && (connections.isNotEmpty() || folders.isNotEmpty())) render()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ACTION_GOOGLE = "google"
        private const val ACTION_ADD = "add"
        private const val ACTION_FOLDERS = "folders"
        private const val ACTION_RECONNECT = "reconnect:"
        private const val ACTION_BROWSE = "browse:"
        private const val DRIVE_ROOT = "drive:root"
        private const val DRIVE_SHARED = "drive:shared"
        private const val ID_PREFIX = "id:"
    }
}

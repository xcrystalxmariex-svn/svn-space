package space.svn.svnspace.ui.settings

import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.remote.GoogleDriveClient
import space.svn.svnspace.data.remote.WebDavClient
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.ui.connections.OAuthHelper
import space.svn.svnspace.util.AuthState

/**
 * Drill-down picker over the real folders of one provider (Google Drive or WebDAV), so a
 * watched folder can back up into a folder that already exists there, or a new one made here.
 *
 * It hands back a slash path like "/Photos/2024": the same form the worker's remotePath
 * already uses. For Drive that path is walked from the top of My Drive by folder name
 * (GoogleDriveClient.ensureFolder), which is why only real folders are listed here, not
 * shortcuts, shared-with-me folders or names containing "/".
 */
class RemoteFolderPicker(
    private val activity: AppCompatActivity,
    private val repo: SvnRepository,
    private val conn: Connection,
    private val initialPath: String?,
    private val onPicked: (String) -> Unit
) {
    private interface Lister {
        val rootLabel: String
        /** Whether the top level itself is a valid destination. */
        val rootSelectable: Boolean
        fun folders(path: String): List<String>
        fun create(parentPath: String, name: String)
        /** A warning to show above the list, or null. */
        fun warning(): String? = null
    }

    private class DriveLister(private val activity: AppCompatActivity, private val connId: String, secret: String) : Lister {
        private val drive = GoogleDriveClient(OAuthHelper.GOOGLE_CLIENT_ID, secret)
        private val ids = HashMap<String, String>().apply { put("/", "root") }
        override val rootLabel = "My Drive"
        override val rootSelectable = false

        override fun folders(path: String): List<String> {
            val id = ids[path] ?: resolve(path)
            return drive.listAllChildren(id, foldersOnly = true)
                .filter { !it.isShortcut && !it.name.contains('/') }
                .map { item ->
                    ids[join(path, item.name)] = item.id
                    item.name
                }
                .distinct()
        }

        /** Walks a saved path down from My Drive by name, without creating anything. */
        private fun resolve(path: String): String {
            var current = "/"
            for (part in path.split('/').filter { it.isNotBlank() }) {
                if (ids[join(current, part)] == null) folders(current)
                current = join(current, part)
                if (ids[current] == null) throw java.io.FileNotFoundException("$current isn't in My Drive")
            }
            return ids[current] ?: "root"
        }

        override fun create(parentPath: String, name: String) {
            val parentId = ids[parentPath] ?: resolve(parentPath)
            ids[join(parentPath, name)] = drive.createFolderIn(parentId, name).id
        }

        override fun warning(): String? = try {
            when (drive.grantedFullDrive()) {
                false -> {
                    AuthState.markNeedsReconnect(activity, connId, AuthState.Reason.LIMITED_SCOPE)
                    "This account has limited access, so only folders svn-space made are listed. " +
                        "Reconnect it in Cloud Accounts to see all your folders."
                }
                else -> null
            }
        } catch (e: GoogleDriveClient.ReauthRequiredException) {
            AuthState.markNeedsReconnect(activity, connId, AuthState.Reason.EXPIRED)
            "The sign-in for this account expired. Reconnect it in Cloud Accounts."
        }
    }

    private class WebDavLister(private val client: WebDavClient) : Lister {
        override val rootLabel = "Server root"
        override val rootSelectable = true
        override fun folders(path: String): List<String> =
            client.list(path).filter { it.isDirectory && !it.name.contains('/') }
                .map { it.name }.sortedBy { it.lowercase() }
        override fun create(parentPath: String, name: String) = client.mkcol(join(parentPath, name))
    }

    private var lister: Lister? = null
    private var path = "/"
    private var names: List<String> = emptyList()

    private val density = activity.resources.displayMetrics.density
    private val txtPath = TextView(activity).apply { setTypeface(null, Typeface.BOLD); textSize = 15f }
    private val txtWarning = TextView(activity).apply { visibility = View.GONE; alpha = 0.85f; setPadding(0, dp(6), 0, 0) }
    private val progress = ProgressBar(activity).apply { visibility = View.GONE }
    private val listView = ListView(activity)
    private val rows = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, ArrayList())
    private lateinit var dialog: AlertDialog

    private fun dp(v: Int) = (v * density).toInt()

    fun show() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(txtPath)
            addView(txtWarning)
            addView(progress)
            addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(340)))
        }
        listView.adapter = rows
        listView.setOnItemClickListener { _, _, position, _ -> onRow(position) }

        dialog = AlertDialog.Builder(activity)
            .setTitle("Back up to… · ${conn.displayName}")
            .setView(root)
            .setPositiveButton("Use this folder", null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Overridden so a click on My Drive's top level can explain instead of closing.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val l = lister ?: return@setOnClickListener
            if (path == "/" && !l.rootSelectable) {
                Toast.makeText(activity, "Open or create a folder inside My Drive first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onPicked(path)
            dialog.dismiss()
        }
        start()
    }

    private fun start() {
        setLoading(true)
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val secret = repo.getSecret(conn.id) ?: error("This account isn't signed in. Reconnect it in Cloud Accounts.")
                    val l: Lister = when (conn.type) {
                        ConnectionType.OAUTH_GOOGLE -> DriveLister(activity, conn.id, secret)
                        ConnectionType.WEBDAV, ConnectionType.CUSTOM_HTTP -> WebDavLister(
                            WebDavClient(
                                baseUrl = conn.host ?: error("Server address missing"),
                                username = conn.username ?: "",
                                password = secret,
                                useTls = conn.useTls
                            )
                        )
                        else -> error("Picking folders isn't supported for this provider yet")
                    }
                    val warning = l.warning()
                    // Open where the folder backs up now, if that folder still exists.
                    val startPath = normalize(initialPath)
                    val opened = if (startPath != "/") runCatching { startPath to l.folders(startPath) }.getOrNull() else null
                    Triple(l, warning, opened ?: ("/" to l.folders("/")))
                }
            }
            result.onSuccess { (l, warning, opened) ->
                lister = l
                setLoading(false)
                warning?.let { txtWarning.text = it; txtWarning.visibility = View.VISIBLE }
                show(opened.first, opened.second)
            }.onFailure { e ->
                setLoading(false)
                txtPath.text = "Couldn't load folders"
                txtWarning.text = e.message ?: e.javaClass.simpleName
                txtWarning.visibility = View.VISIBLE
            }
        }
    }

    private fun show(newPath: String, folderNames: List<String>) {
        path = newPath
        names = folderNames
        val l = lister
        txtPath.text = (l?.rootLabel ?: "") + if (path == "/") "" else path.replace("/", " › ")
        rows.clear()
        if (path != "/") rows.add("↑  Up one level")
        rows.add("+  New folder here…")
        if (folderNames.isEmpty()) rows.add("(no folders here)")
        folderNames.forEach { rows.add("\u25b8  $it") }
        rows.notifyDataSetChanged()
    }

    private fun onRow(position: Int) {
        if (lister == null) return
        var i = position
        if (path != "/") {
            if (i == 0) { open(path.substringBeforeLast('/', "").ifBlank { "/" }); return }
            i--
        }
        if (i == 0) { askNewFolder(); return }
        i--
        val name = names.getOrNull(i) ?: return
        open(join(path, name))
    }

    private fun open(target: String) {
        val l = lister ?: return
        setLoading(true)
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { l.folders(target) } }
            setLoading(false)
            result.onSuccess { show(target, it) }
                .onFailure { Toast.makeText(activity, "Couldn't open: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun askNewFolder() {
        val l = lister ?: return
        val input = EditText(activity).apply {
            hint = "Folder name"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val frame = FrameLayout(activity).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        AlertDialog.Builder(activity)
            .setTitle("New folder in ${if (path == "/") l.rootLabel else path.substringAfterLast('/')}")
            .setView(frame)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isBlank() -> return@setPositiveButton
                    name.contains('/') -> Toast.makeText(activity, "A folder name can't contain /", Toast.LENGTH_SHORT).show()
                    names.any { it.equals(name, ignoreCase = true) } -> open(join(path, names.first { it.equals(name, true) }))
                    else -> {
                        setLoading(true)
                        activity.lifecycleScope.launch {
                            val created = join(path, name)
                            val result = withContext(Dispatchers.IO) {
                                runCatching { l.create(path, name); l.folders(created) }
                            }
                            setLoading(false)
                            result.onSuccess { show(created, it) }
                                .onFailure {
                                    Toast.makeText(activity, "Couldn't create the folder: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        listView.isEnabled = !loading
        if (::dialog.isInitialized) dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !loading && lister != null
    }

    companion object {
        fun join(parent: String, name: String) = if (parent == "/") "/$name" else "$parent/$name"

        fun normalize(path: String?): String {
            val parts = path.orEmpty().split('/').filter { it.isNotBlank() }
            return "/" + parts.joinToString("/")
        }
    }
}

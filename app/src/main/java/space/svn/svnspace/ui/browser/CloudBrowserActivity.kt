package space.svn.svnspace.ui.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.svn.svnspace.R
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.remote.GoogleDriveClient
import space.svn.svnspace.data.remote.WebDavClient
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.databinding.ActivityCloudBrowserBinding
import space.svn.svnspace.databinding.ItemCloudFileBinding
import space.svn.svnspace.ui.common.ThemedActivity
import space.svn.svnspace.ui.connections.OAuthHelper
import space.svn.svnspace.ui.connections.Reconnect
import space.svn.svnspace.util.AuthState
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Browses everything in one connected account (not only what svn-space backed up):
 * Google Drive (My Drive, Shared with me, shared drives) or a WebDAV server.
 * Open, share, save to the phone, upload, new folder, rename, move and delete.
 * Drive deletes go to the Drive trash; WebDAV deletes are permanent and say so.
 */
class CloudBrowserActivity : ThemedActivity() {

    private lateinit var binding: ActivityCloudBrowserBinding
    private lateinit var repo: SvnRepository
    private var connection: Connection? = null
    private var source: CloudSource? = null

    /** Folders from the account root down to the one on screen. */
    private val stack = ArrayList<CloudLocation>()
    private val items = ArrayList<CloudItem>()
    private var nextPageToken: String? = null
    private var loadJob: Job? = null
    private var loadingMore = false

    /** Item being moved, and the folder it came from, while the move bar is showing. */
    private var moving: Pair<CloudItem, CloudLocation>? = null
    /** Item waiting for the "save to phone" picker. */
    private var pendingSave: CloudItem? = null

    private val adapter = Adapter()

    private val pickUpload = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) uploadUris(uris)
    }

    private val pickSaveTarget = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val item = pendingSave
        pendingSave = null
        if (uri != null && item != null) saveToPhone(item, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloudBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repo = SvnRepository(this)

        val lm = LinearLayoutManager(this)
        binding.recycler.layoutManager = lm
        binding.recycler.adapter = adapter
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && nextPageToken != null && !loadingMore &&
                    lm.findLastVisibleItemPosition() >= items.size - 10
                ) loadMore()
            }
        })
        binding.swipe.setOnRefreshListener { reload() }
        binding.fabAdd.setOnClickListener { showNewMenu() }
        binding.btnMoveCancel.setOnClickListener { endMove() }
        binding.btnMoveHere.setOnClickListener { finishMove() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stack.size > 1) {
                    stack.removeAt(stack.size - 1)
                    reload()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val id = intent.getStringExtra(EXTRA_CONNECTION_ID)
        if (id == null) {
            finish()
            return
        }
        lifecycleScope.launch { openConnection(id) }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a reconnect: rebuild the source with the new sign-in.
        val conn = connection ?: return
        if (bannerShownFor != null && AuthState.reasonFor(this, conn.id) == null) {
            lifecycleScope.launch { openConnection(conn.id) }
        }
    }

    // ---- Setup ----

    private suspend fun openConnection(id: String) {
        val conn = repo.getConnection(id)
        if (conn == null) {
            Toast.makeText(this, "That account was removed", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        connection = conn
        supportActionBar?.title = conn.displayName
        val secret = repo.getSecret(conn.id)
        if (secret == null) {
            AuthState.markNeedsReconnect(this, conn.id, AuthState.Reason.MISSING_LOGIN)
            showBanner(AuthState.Reason.MISSING_LOGIN)
            showEmpty("Sign in to this account again to see its files.")
            return
        }
        val src: CloudSource = when (conn.type) {
            ConnectionType.OAUTH_GOOGLE -> DriveSource(GoogleDriveClient(OAuthHelper.GOOGLE_CLIENT_ID, secret))
            ConnectionType.WEBDAV, ConnectionType.CUSTOM_HTTP -> {
                val host = conn.host
                if (host.isNullOrBlank()) {
                    showEmpty("This account has no server address. Edit it in Cloud Accounts.")
                    return
                }
                WebDavSource(WebDavClient(host, conn.username ?: "", secret, conn.useTls), conn.displayName)
            }
            else -> {
                showEmpty("Browsing this kind of account isn't supported yet.")
                return
            }
        }
        source = src
        hideBanner()
        AuthState.reasonFor(this, conn.id)?.let { if (it == AuthState.Reason.LIMITED_SCOPE) showBanner(it) }
        stack.clear()
        val start = if (conn.type == ConnectionType.WEBDAV || conn.type == ConnectionType.CUSTOM_HTTP) {
            CloudLocation("/", conn.displayName)
        } else src.root
        stack.add(start)
        reload()
        if (conn.type == ConnectionType.OAUTH_GOOGLE) checkDriveScope(src)
    }

    /** Old drive.file logins only see files svn-space made: say so and offer the reconnect. */
    private fun checkDriveScope(src: CloudSource) {
        val conn = connection ?: return
        val drive = (src as? DriveSource) ?: return
        lifecycleScope.launch {
            val full = withContext(Dispatchers.IO) {
                try {
                    drive.grantedFullDrive()
                } catch (e: GoogleDriveClient.ReauthRequiredException) {
                    null
                }
            }
            if (full == false) {
                AuthState.markNeedsReconnect(this@CloudBrowserActivity, conn.id, AuthState.Reason.LIMITED_SCOPE)
                showBanner(AuthState.Reason.LIMITED_SCOPE)
            } else if (full == true && AuthState.reasonFor(this@CloudBrowserActivity, conn.id) == AuthState.Reason.LIMITED_SCOPE) {
                AuthState.clear(this@CloudBrowserActivity, conn.id)
                hideBanner()
            }
        }
    }

    private var bannerShownFor: AuthState.Reason? = null

    private fun showBanner(reason: AuthState.Reason) {
        bannerShownFor = reason
        binding.bannerCard.visibility = View.VISIBLE
        binding.txtBanner.text = when (reason) {
            AuthState.Reason.LIMITED_SCOPE ->
                "This account was connected with limited access, so you only see files svn-space made. Reconnect to see everything."
            AuthState.Reason.EXPIRED -> "The sign-in for this account expired. Reconnect to keep browsing and backing up."
            AuthState.Reason.MISSING_LOGIN -> "This account needs its login again."
        }
        binding.btnBanner.setOnClickListener {
            val conn = connection ?: return@setOnClickListener
            Reconnect.start(this, lifecycleScope, repo, conn) {
                lifecycleScope.launch { openConnection(conn.id) }
            }
        }
    }

    private fun hideBanner() {
        bannerShownFor = null
        binding.bannerCard.visibility = View.GONE
    }

    // ---- Listing ----

    private val current: CloudLocation? get() = stack.lastOrNull()

    private fun reload() {
        val src = source ?: run { binding.swipe.isRefreshing = false; return }
        val loc = current ?: return
        loadJob?.cancel()
        renderBreadcrumbs()
        updateFab()
        nextPageToken = null
        loadingMore = false
        binding.txtEmpty.visibility = View.GONE
        if (!binding.swipe.isRefreshing) binding.progress.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { src.list(loc, null) } }
            binding.progress.visibility = View.GONE
            binding.swipe.isRefreshing = false
            if (loc != current) return@launch
            result.onSuccess { page ->
                items.clear()
                items.addAll(page.items)
                nextPageToken = page.nextPageToken
                adapter.notifyDataSetChanged()
                if (items.isEmpty()) showEmpty("This folder is empty") else binding.txtEmpty.visibility = View.GONE
            }.onFailure { e ->
                items.clear()
                adapter.notifyDataSetChanged()
                handleError(e, "Couldn't load this folder")
            }
        }
    }

    private fun loadMore() {
        val src = source ?: return
        val loc = current ?: return
        val token = nextPageToken ?: return
        loadingMore = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { src.list(loc, token) } }
            loadingMore = false
            if (loc != current) return@launch
            result.onSuccess { page ->
                val start = items.size
                items.addAll(page.items)
                nextPageToken = page.nextPageToken
                adapter.notifyItemRangeInserted(start, page.items.size)
            }.onFailure { e -> handleError(e, "Couldn't load more files") }
        }
    }

    private fun showEmpty(text: String) {
        binding.progress.visibility = View.GONE
        binding.swipe.isRefreshing = false
        binding.txtEmpty.text = text
        binding.txtEmpty.visibility = View.VISIBLE
    }

    /** Sign-in problems get the reconnect banner; everything else gets its message. */
    private fun handleError(e: Throwable, what: String) {
        val conn = connection
        when (e) {
            is GoogleDriveClient.ScopeUpgradeRequiredException -> {
                conn?.let { AuthState.markNeedsReconnect(this, it.id, AuthState.Reason.LIMITED_SCOPE) }
                showBanner(AuthState.Reason.LIMITED_SCOPE)
                if (items.isEmpty()) showEmpty("Reconnect this account to see these files.")
            }
            is GoogleDriveClient.ReauthRequiredException -> {
                conn?.let { AuthState.markNeedsReconnect(this, it.id, AuthState.Reason.EXPIRED) }
                showBanner(AuthState.Reason.EXPIRED)
                if (items.isEmpty()) showEmpty("Reconnect this account to see its files.")
            }
            is WebDavClient.AuthException -> {
                conn?.let { AuthState.markNeedsReconnect(this, it.id, AuthState.Reason.MISSING_LOGIN) }
                showBanner(AuthState.Reason.MISSING_LOGIN)
                if (items.isEmpty()) showEmpty("The server refused the login.")
            }
            else -> {
                val msg = "$what: ${e.message ?: e.javaClass.simpleName}"
                if (items.isEmpty()) showEmpty(msg) else Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderBreadcrumbs() {
        val bar = binding.breadcrumbs
        bar.removeAllViews()
        val pad = (8 * resources.displayMetrics.density).toInt()
        stack.forEachIndexed { i, loc ->
            if (i > 0) {
                bar.addView(TextView(this).apply {
                    text = "›"
                    setTextColor(currentTextColor(isLast = false))
                })
            }
            bar.addView(TextView(this).apply {
                text = loc.name
                setPadding(pad, pad / 2, pad, pad / 2)
                val last = i == stack.size - 1
                setTextColor(currentTextColor(last))
                if (last) setTypeface(typeface, android.graphics.Typeface.BOLD)
                isClickable = !last
                if (!last) setOnClickListener {
                    while (stack.size > i + 1) stack.removeAt(stack.size - 1)
                    reload()
                }
            })
        }
        binding.breadcrumbScroll.post { binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun currentTextColor(isLast: Boolean): Int {
        val attr = if (isLast) com.google.android.material.R.attr.colorOnSurface
        else com.google.android.material.R.attr.colorOnSurfaceVariant
        return com.google.android.material.color.MaterialColors.getColor(binding.root, attr)
    }

    private fun updateFab() {
        val writable = current?.writable == true
        binding.fabAdd.visibility = if (writable && moving == null) View.VISIBLE else View.GONE
        if (moving != null) {
            binding.btnMoveHere.isEnabled = writable
        }
    }

    // ---- Opening ----

    private fun onItemClick(item: CloudItem) {
        if (item.isFolder) {
            val target = item.target ?: CloudLocation(item.id, item.name)
            stack.add(target)
            reload()
            return
        }
        if (moving != null) return
        openFile(item, share = false)
    }

    /** Downloads to the app's cache, then hands the file to another app (open with / share). */
    private fun openFile(item: CloudItem, share: Boolean) {
        val src = source ?: return
        if (item.isGoogleDoc && item.exportFormat == null) {
            openInBrowser(item)
            return
        }
        val (fileName, mime) = localNameAndMime(item)
        val dir = File(cacheDir, "cloud/${UUID.randomUUID()}").apply { mkdirs() }
        val out = File(dir, fileName)
        val dialog = progressDialog("Downloading ${item.name}…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    src.download(item, out) { done, total ->
                        if (total > 0) runOnUiThread {
                            dialog.setMessage("Downloading ${item.name}… ${(done * 100 / total).toInt()}%")
                        }
                    }
                }
            }
            dialog.dismiss()
            result.onSuccess {
                val uri = FileProvider.getUriForFile(this@CloudBrowserActivity, "$packageName.files", out)
                val intent = if (share) {
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "Share ${item.name}"
                    )
                } else {
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@CloudBrowserActivity, "No app on this phone opens this kind of file", Toast.LENGTH_LONG).show()
                }
            }.onFailure { e ->
                out.delete()
                handleError(e, "Couldn't download ${item.name}")
            }
        }
    }

    private fun openInBrowser(item: CloudItem) {
        val link = item.webLink
        if (link == null) {
            Toast.makeText(this, "This file can't be opened from here", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_LONG).show()
        }
    }

    /** Google Docs get their export extension (report → report.docx). */
    private fun localNameAndMime(item: CloudItem): Pair<String, String> {
        val export = item.exportFormat
        if (export != null) {
            val name = if (item.name.endsWith(".${export.second}", true)) item.name else "${item.name}.${export.second}"
            return safeName(name) to export.first
        }
        val ext = item.name.substringAfterLast('.', "").lowercase()
        val mime = item.mimeType?.takeIf { it.contains('/') && !it.startsWith("application/vnd.google-apps") }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        return safeName(item.name) to mime
    }

    private fun safeName(name: String) = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "file" }

    private fun saveToPhone(item: CloudItem, target: Uri) {
        val src = source ?: return
        val tmp = File(cacheDir, "cloud/${UUID.randomUUID()}").apply { parentFile?.mkdirs() }
        val dialog = progressDialog("Saving ${item.name}…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    src.download(item, tmp) { _, _ -> }
                    contentResolver.openOutputStream(target)?.use { output ->
                        tmp.inputStream().use { it.copyTo(output) }
                    } ?: throw java.io.IOException("Couldn't write to the chosen place")
                }
            }
            tmp.delete()
            dialog.dismiss()
            result.onSuccess {
                Toast.makeText(this@CloudBrowserActivity, "Saved ${item.name}", Toast.LENGTH_SHORT).show()
            }.onFailure { e -> handleError(e, "Couldn't save ${item.name}") }
        }
    }

    // ---- Item actions ----

    private fun showItemMenu(item: CloudItem) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (!item.isFolder) {
            actions.add("Open" to { openFile(item, share = false) })
            if (!item.isGoogleDoc || item.exportFormat != null) {
                actions.add("Share" to { openFile(item, share = true) })
                actions.add("Save to phone" to {
                    pendingSave = item
                    pickSaveTarget.launch(localNameAndMime(item).first)
                })
            }
        } else {
            actions.add("Open" to { onItemClick(item) })
        }
        if (item.webLink != null && connection?.type == ConnectionType.OAUTH_GOOGLE) {
            actions.add("Open in Drive" to { openInBrowser(item) })
        }
        if (item.canRename) actions.add("Rename" to { askRename(item) })
        if (item.canMove) actions.add("Move" to { startMove(item) })
        if (item.canDelete) actions.add("Delete" to { confirmDelete(item) })
        actions.add("Details" to { showDetails(item) })

        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun showDetails(item: CloudItem) {
        val lines = buildList {
            add(if (item.isFolder) "Folder" else (item.mimeType ?: "File"))
            if (item.size >= 0) add("Size: ${Formatter.formatFileSize(this@CloudBrowserActivity, item.size)}")
            if (item.modifiedAt > 0) add("Modified: ${DateUtils.formatDateTime(this@CloudBrowserActivity, item.modifiedAt,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)}")
            add("In: ${stack.joinToString(" › ") { it.name }}")
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun askText(title: String, initial: String, action: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSingleLine()
            val dot = initial.lastIndexOf('.')
            if (dot > 0) setSelection(0, dot) else selectAll()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton(action) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty() && !text.contains('/')) onOk(text)
                else Toast.makeText(this, "Enter a name without slashes", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askRename(item: CloudItem) {
        val loc = current ?: return
        askText("Rename", item.name, "Rename") { newName ->
            if (newName == item.name) return@askText
            runAction("Renaming…", "Couldn't rename ${item.name}") { it.rename(item, loc, newName) }
        }
    }

    private fun confirmDelete(item: CloudItem) {
        val src = source ?: return
        val loc = current ?: return
        val message = if (src.deleteIsTrash) {
            "It goes to the Drive trash, where you can restore it for 30 days."
        } else {
            "This server has no trash, so it's deleted for good" + (if (item.isFolder) ", with everything inside it." else ".")
        }
        AlertDialog.Builder(this)
            .setTitle("Delete ${item.name}?")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                runAction("Deleting…", "Couldn't delete ${item.name}") { it.delete(item, loc) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startMove(item: CloudItem) {
        val loc = current ?: return
        moving = item to loc
        binding.moveBar.visibility = View.VISIBLE
        binding.txtMove.text = "Moving ${item.name}. Open the folder to move it to."
        updateFab()
    }

    private fun endMove() {
        moving = null
        binding.moveBar.visibility = View.GONE
        updateFab()
    }

    private fun finishMove() {
        val (item, from) = moving ?: return
        val to = current ?: return
        if (to.key == from.key && to.kind == from.kind) {
            Toast.makeText(this, "It's already in this folder", Toast.LENGTH_SHORT).show()
            return
        }
        if (item.isFolder && item.target?.key == to.key) {
            Toast.makeText(this, "Can't move a folder into itself", Toast.LENGTH_SHORT).show()
            return
        }
        endMove()
        runAction("Moving…", "Couldn't move ${item.name}") { it.move(item, from, to) }
    }

    // ---- New folder / upload ----

    private fun showNewMenu() {
        if (current?.writable != true) return
        AlertDialog.Builder(this)
            .setItems(arrayOf("New folder", "Upload files")) { _, which ->
                when (which) {
                    0 -> askText("New folder", "", "Create") { name ->
                        val loc = current ?: return@askText
                        runAction("Creating folder…", "Couldn't create the folder") { it.createFolder(loc, name) }
                    }
                    1 -> pickUpload.launch(arrayOf("*/*"))
                }
            }
            .show()
    }

    private fun uploadUris(uris: List<Uri>) {
        val src = source ?: return
        val loc = current ?: return
        val dialog = progressDialog("Uploading…")
        lifecycleScope.launch {
            var done = 0
            val failures = ArrayList<String>()
            for (uri in uris) {
                val name = displayName(uri) ?: "upload_${System.currentTimeMillis()}"
                dialog.setMessage("Uploading $name (${done + 1} of ${uris.size})…")
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val tmp = File(cacheDir, "cloud/${UUID.randomUUID()}").apply { parentFile?.mkdirs() }
                        try {
                            contentResolver.openInputStream(uri)?.use { input ->
                                FileOutputStream(tmp).use { input.copyTo(it) }
                            } ?: throw java.io.IOException("Couldn't read the file")
                            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                            src.upload(loc, tmp, name, mime)
                        } finally {
                            tmp.delete()
                        }
                    }
                }
                result.onSuccess { done++ }.onFailure { e ->
                    if (e is GoogleDriveClient.ReauthRequiredException || e is WebDavClient.AuthException) {
                        dialog.dismiss()
                        handleError(e, "Upload failed")
                        return@launch
                    }
                    failures.add("$name: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            dialog.dismiss()
            val msg = if (failures.isEmpty()) "Uploaded $done file" + (if (done == 1) "" else "s")
            else "Uploaded $done of ${uris.size}. ${failures.first()}"
            Toast.makeText(this@CloudBrowserActivity, msg, Toast.LENGTH_LONG).show()
            reload()
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Runs one file operation off the main thread, then refreshes the folder. */
    private fun runAction(progress: String, failure: String, op: (CloudSource) -> Unit) {
        val src = source ?: return
        val dialog = progressDialog(progress)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { op(src) } }
            dialog.dismiss()
            result.onSuccess { reload() }.onFailure { e -> handleError(e, failure) }
        }
    }

    private fun progressDialog(message: String): AlertDialog =
        AlertDialog.Builder(this).setMessage(message).setCancelable(false).show()

    // ---- List ----

    private inner class Holder(val b: ItemCloudFileBinding) : RecyclerView.ViewHolder(b.root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemCloudFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = items[position]
            h.b.txtName.text = item.name
            h.b.txtIcon.text = iconFor(item)
            h.b.txtMeta.text = metaFor(item)
            h.b.root.setOnClickListener { onItemClick(item) }
            h.b.root.setOnLongClickListener { showItemMenu(item); true }
            val hasMenu = item.canRename || item.canDelete || item.canMove || !item.isFolder
            h.b.btnMore.visibility = if (hasMenu) View.VISIBLE else View.INVISIBLE
            h.b.btnMore.setOnClickListener { showItemMenu(item) }
        }
    }

    private fun metaFor(item: CloudItem): String {
        val parts = ArrayList<String>()
        if (item.isFolder) {
            when {
                item.mimeType == "shared-drive" -> parts.add("Shared drive")
                item.id == "shared" -> parts.add("Files others shared with you")
                item.id == "root" && current?.kind == CloudLocation.Kind.ACCOUNT_ROOT -> parts.add("Your files")
                else -> parts.add("Folder")
            }
        } else if (item.size >= 0) {
            parts.add(Formatter.formatShortFileSize(this, item.size))
        } else if (item.isGoogleDoc) {
            parts.add(googleKind(item.mimeType))
        }
        if (item.modifiedAt > 0) {
            parts.add(DateUtils.getRelativeTimeSpanString(item.modifiedAt, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString())
        }
        return parts.joinToString(" · ")
    }

    private fun googleKind(mime: String?): String = when (mime) {
        "application/vnd.google-apps.document" -> "Google Doc"
        "application/vnd.google-apps.spreadsheet" -> "Google Sheet"
        "application/vnd.google-apps.presentation" -> "Google Slides"
        "application/vnd.google-apps.drawing" -> "Google Drawing"
        "application/vnd.google-apps.form" -> "Google Form"
        else -> "Google file"
    }

    private fun iconFor(item: CloudItem): String {
        if (item.isFolder) {
            return when {
                item.mimeType == "shared-drive" -> "\uD83D\uDDC4"   // file cabinet
                item.id == "shared" -> "\uD83D\uDC65"               // people
                else -> "\uD83D\uDCC1"                               // folder
            }
        }
        val mime = item.mimeType ?: ""
        val ext = item.name.substringAfterLast('.', "").lowercase()
        return when {
            mime == "application/vnd.google-apps.spreadsheet" || ext in setOf("xls", "xlsx", "csv", "ods") -> "\uD83D\uDCCA"
            mime == "application/vnd.google-apps.presentation" || ext in setOf("ppt", "pptx", "odp") -> "\uD83D\uDCFD"
            mime.startsWith("application/vnd.google-apps.") -> "\uD83D\uDCDD"
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic") -> "\uD83D\uDDBC"
            mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "avi", "webm") -> "\uD83C\uDFAC"
            mime.startsWith("audio/") || ext in setOf("mp3", "wav", "flac", "ogg", "aac", "m4a") -> "\uD83C\uDFB5"
            mime == "application/pdf" || ext == "pdf" -> "\uD83D\uDCD5"
            ext in setOf("zip", "rar", "7z", "tar", "gz") -> "\uD83D\uDDDC"
            ext == "apk" -> "\uD83E\uDD16"
            else -> "\uD83D\uDCC4"
        }
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"

        fun intent(context: Context, connectionId: String): Intent =
            Intent(context, CloudBrowserActivity::class.java).putExtra(EXTRA_CONNECTION_ID, connectionId)
    }
}

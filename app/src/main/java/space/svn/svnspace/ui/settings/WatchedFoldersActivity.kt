package space.svn.svnspace.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.model.FileFilter
import space.svn.svnspace.data.model.WatchedFolder
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.databinding.ActivityWatchedFoldersBinding
import space.svn.svnspace.databinding.DialogFolderSettingsBinding
import space.svn.svnspace.ui.connections.AddConnectionActivity
import space.svn.svnspace.util.StorageAccess
import space.svn.svnspace.util.ThemeManager
import java.util.UUID

/**
 * Watched folders: add (system picker or Downloads), toggle, remove, and the per-folder
 * "advanced" settings — which connection it backs up to, the remote folder, and file types.
 *
 * A folder with no connection is never backed up, so a new folder is given the only
 * connection automatically, or its settings open right away when there are several.
 */
class WatchedFoldersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWatchedFoldersBinding
    private lateinit var repo: SvnRepository
    private lateinit var adapter: WatchedFolderAdapter

    private var folders: List<WatchedFolder> = emptyList()
    private var connections: List<Connection> = emptyList()
    private var pendingDownloadsAdd = false
    private var advancedHandled = false

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, "Android didn't allow access to that folder", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        val name = DocumentFile.fromTreeUri(this, uri)?.name
            ?: uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null }
            ?: "Folder"
        addFolder(uri.toString(), name)
    }

    /** Returns from the system "All files access" screen (Android 11+). */
    private val allFilesAccessScreen = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        finishDownloadsAdd()
    }

    /** Android 8–10: plain storage permission. */
    private val legacyStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        finishDownloadsAdd()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyToActivity(this)
        super.onCreate(savedInstanceState)
        binding = ActivityWatchedFoldersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Watched Folders"

        repo = SvnRepository(this)
        advancedHandled = savedInstanceState?.getBoolean(STATE_ADVANCED_HANDLED) ?: false
        adapter = WatchedFolderAdapter(
            onToggle = { folder, enabled ->
                lifecycleScope.launch { repo.saveWatchedFolder(folder.copy(isEnabled = enabled)) }
            },
            onDelete = { folder -> confirmDelete(folder) },
            onOpen = { folder -> onFolderTapped(folder) },
            describe = { folder -> describe(folder) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fabAdd.setOnClickListener { showAddChoices() }

        lifecycleScope.launch {
            repo.observeConnections().collect { list ->
                connections = list.filter { it.isEnabled }
                adapter.notifyDataSetChanged()
            }
        }
        lifecycleScope.launch {
            repo.observeWatchedFolders().collect { list ->
                folders = list
                adapter.submitList(list)
                maybeOpenAdvanced()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permission state may have changed in system settings.
        adapter.notifyDataSetChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ADVANCED_HANDLED, advancedHandled)
    }

    // ── Advanced entry point from Settings ───────────────────────────────────────

    private fun maybeOpenAdvanced() {
        if (advancedHandled || !intent.getBooleanExtra(EXTRA_OPEN_ADVANCED, false)) return
        advancedHandled = true
        lifecycleScope.launch {
            connections = repo.observeConnections().first().filter { it.isEnabled }
            when (folders.size) {
                0 -> AlertDialog.Builder(this@WatchedFoldersActivity)
                    .setTitle("Per-folder settings")
                    .setMessage("Add a watched folder first. Its settings open as soon as it's added, and you can tap any folder in this list to change them later.")
                    .setPositiveButton("Add folder") { _, _ -> showAddChoices() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                1 -> showFolderSettings(folders[0])
                else -> AlertDialog.Builder(this@WatchedFoldersActivity)
                    .setTitle("Choose a folder")
                    .setItems(folders.map { it.displayPath }.toTypedArray()) { _, which ->
                        showFolderSettings(folders[which])
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    // ── Adding folders ───────────────────────────────────────────────────────

    private fun showAddChoices() {
        AlertDialog.Builder(this)
            .setTitle("Add a watched folder")
            .setItems(arrayOf("Downloads", "Choose another folder…")) { _, which ->
                if (which == 0) addDownloads() else folderPicker.launch(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Android 11+ won't let the system folder picker hand out the Downloads root, so
     * Downloads is watched as a plain path with All files access instead.
     */
    private fun addDownloads() {
        val uri = StorageAccess.downloadsUri()
        if (folders.any { it.localUri == uri }) {
            Toast.makeText(this, "Downloads is already watched", Toast.LENGTH_SHORT).show()
            return
        }
        if (StorageAccess.hasAllFilesAccess(this)) {
            addFolder(uri, "Downloads")
            return
        }
        pendingDownloadsAdd = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("Allow access to Downloads")
                .setMessage("Android only lets backup apps read the Downloads folder with \"All files access\". On the next screen, turn on \"Allow access to manage all files\" for svn-space, then come back.")
                .setPositiveButton("Open settings") { _, _ -> openAllFilesAccessScreen() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> pendingDownloadsAdd = false }
                .show()
        } else {
            legacyStoragePermission.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun openAllFilesAccessScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val perApp = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            allFilesAccessScreen.launch(perApp)
        } catch (e: Exception) {
            try {
                allFilesAccessScreen.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                pendingDownloadsAdd = false
                Toast.makeText(this, "Open Settings → Apps → svn-space → All files access", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finishDownloadsAdd() {
        val wanted = pendingDownloadsAdd
        pendingDownloadsAdd = false
        adapter.notifyDataSetChanged()
        if (StorageAccess.hasAllFilesAccess(this)) {
            if (wanted) {
                val uri = StorageAccess.downloadsUri()
                if (folders.none { it.localUri == uri }) addFolder(uri, "Downloads")
            }
        } else if (wanted) {
            Toast.makeText(this, "Downloads needs All files access to be watched", Toast.LENGTH_LONG).show()
        }
    }

    private fun addFolder(localUri: String, name: String) {
        lifecycleScope.launch {
            val conns = repo.observeConnections().first().filter { it.isEnabled }
            connections = conns
            val folder = WatchedFolder(
                id = UUID.randomUUID().toString(),
                localUri = localUri,
                displayPath = name,
                isEnabled = true,
                connectionId = conns.singleOrNull()?.id
            )
            repo.saveWatchedFolder(folder)
            when {
                conns.size == 1 -> Toast.makeText(
                    this@WatchedFoldersActivity,
                    "Added $name → backs up to ${conns[0].displayName}",
                    Toast.LENGTH_LONG
                ).show()
                else -> {
                    Toast.makeText(this@WatchedFoldersActivity, "Added: $name", Toast.LENGTH_SHORT).show()
                    // No connection, or several: ask where this folder should go.
                    showFolderSettings(folder)
                }
            }
        }
    }

    // ── Rows ─────────────────────────────────────────────────────────────────

    private fun describe(folder: WatchedFolder): String {
        StorageAccess.problemWith(this, folder.localUri)?.let { return it }
        val conn = connections.firstOrNull { it.id == folder.connectionId }
        val dest = conn?.displayName ?: "No backup destination – tap to set"
        val filter = when (folder.fileFilter) {
            FileFilter.ANY -> "All files"
            FileFilter.IMAGES -> "Images"
            FileFilter.VIDEOS -> "Videos"
            FileFilter.DOCUMENTS -> "Documents"
            FileFilter.AUDIO -> "Audio"
            FileFilter.CUSTOM -> folder.customExtensions.joinToString(", ").ifBlank { "Custom" }
        }
        return if (conn == null) dest else "→ $dest · $filter"
    }

    private fun onFolderTapped(folder: WatchedFolder) {
        if (StorageAccess.isFileUri(folder.localUri) && !StorageAccess.hasAllFilesAccess(this)) {
            pendingDownloadsAdd = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                openAllFilesAccessScreen()
            } else {
                legacyStoragePermission.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
            return
        }
        showFolderSettings(folder)
    }

    private fun confirmDelete(folder: WatchedFolder) {
        AlertDialog.Builder(this)
            .setTitle("Stop watching ${folder.displayPath}?")
            .setMessage("Nothing is deleted from your phone or the cloud.")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    repo.deleteWatchedFolder(folder.id)
                    if (!StorageAccess.isFileUri(folder.localUri)) {
                        try {
                            contentResolver.releasePersistableUriPermission(
                                Uri.parse(folder.localUri),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        } catch (_: Exception) { }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Per-folder advanced settings ─────────────────────────────────────────

    private fun showFolderSettings(folder: WatchedFolder) {
        val b = DialogFolderSettingsBinding.inflate(layoutInflater)
        b.txtFolderPath.text = if (StorageAccess.isFileUri(folder.localUri)) {
            StorageAccess.fileOf(folder.localUri).absolutePath
        } else folder.displayPath

        val conns = connections
        if (conns.isEmpty()) {
            b.spinnerConnection.visibility = View.GONE
            b.txtNoConnections.visibility = View.VISIBLE
        } else {
            b.spinnerConnection.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, conns.map { it.displayName }
            )
            val idx = conns.indexOfFirst { it.id == folder.connectionId }
            b.spinnerConnection.setSelection(if (idx >= 0) idx else 0)
        }

        b.editRemotePath.setText(folder.remotePath.orEmpty())

        val filters = FileFilter.entries
        val filterLabels = listOf("All files", "Images", "Videos", "Documents", "Audio", "Custom extensions…")
        b.spinnerFilter.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, filterLabels.take(filters.size)
        )
        b.spinnerFilter.setSelection(filters.indexOf(folder.fileFilter).coerceAtLeast(0))
        b.editExtensions.setText(folder.customExtensions.joinToString(", "))
        b.spinnerFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                b.layoutExtensions.visibility =
                    if (filters.getOrNull(position) == FileFilter.CUSTOM) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(folder.displayPath)
            .setView(b.root)
            .setPositiveButton("Save") { _, _ ->
                val conn = if (conns.isEmpty()) null else conns.getOrNull(b.spinnerConnection.selectedItemPosition)
                val filter = filters.getOrNull(b.spinnerFilter.selectedItemPosition) ?: FileFilter.ANY
                val exts = b.editExtensions.text?.toString().orEmpty()
                    .split(',', ' ', ';')
                    .map { it.trim().removePrefix(".").lowercase() }
                    .filter { it.isNotBlank() }
                    .distinct()
                val remote = b.editRemotePath.text?.toString()?.trim().orEmpty()
                val updated = folder.copy(
                    connectionId = conn?.id ?: folder.connectionId,
                    remotePath = remote.ifBlank { null },
                    fileFilter = if (filter == FileFilter.CUSTOM && exts.isEmpty()) FileFilter.ANY else filter,
                    customExtensions = exts
                )
                lifecycleScope.launch {
                    repo.saveWatchedFolder(updated)
                    Toast.makeText(this@WatchedFoldersActivity, "Saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (conns.isEmpty()) {
            builder.setNeutralButton("Add connection") { _, _ ->
                startActivity(Intent(this, AddConnectionActivity::class.java))
            }
        }
        builder.show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_OPEN_ADVANCED = "open_advanced"
        private const val STATE_ADVANCED_HANDLED = "advanced_handled"
    }
}

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
import space.svn.svnspace.ui.common.ThemedActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.remote.GoogleDriveClient
import space.svn.svnspace.data.model.FileFilter
import space.svn.svnspace.data.model.WatchedFolder
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.databinding.ActivityWatchedFoldersBinding
import space.svn.svnspace.databinding.DialogFolderSettingsBinding
import space.svn.svnspace.ui.connections.AddConnectionActivity
import space.svn.svnspace.util.StorageAccess
import space.svn.svnspace.worker.BackupScheduler
import space.svn.svnspace.util.ThemeManager
import java.util.UUID

/**
 * Watched folders: add (system picker or Downloads), toggle, remove, and the per-folder
 * "advanced" settings — which connection it backs up to, the remote folder, and file types.
 *
 * A folder with no connection is never backed up, so a new folder is given the only
 * connection automatically, or its settings open right away when there are several.
 */
class WatchedFoldersActivity : ThemedActivity() {

    private lateinit var binding: ActivityWatchedFoldersBinding
    private lateinit var repo: SvnRepository
    private lateinit var adapter: WatchedFolderAdapter

    private var folders: List<WatchedFolder> = emptyList()
    private var connections: List<Connection> = emptyList()
    private var pendingAdd: Pair<String, String>? = null
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
        super.onCreate(savedInstanceState)
        binding = ActivityWatchedFoldersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById(space.svn.svnspace.R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Watch Folders"

        binding.txtInterval.text = BackupScheduler.intervalLabel(BackupScheduler.intervalMinutes(this))
        binding.cardInterval.setOnClickListener { chooseInterval() }

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
                binding.txtEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
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

    private fun chooseInterval() {
        val current = BackupScheduler.INTERVAL_MINUTES.indexOf(BackupScheduler.intervalMinutes(this))
        AlertDialog.Builder(this)
            .setTitle("Scan interval")
            .setSingleChoiceItems(BackupScheduler.INTERVAL_LABELS, current) { dialog, which ->
                val minutes = BackupScheduler.INTERVAL_MINUTES[which]
                BackupScheduler.updateInterval(this, minutes)
                binding.txtInterval.text = BackupScheduler.intervalLabel(minutes)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddChoices() {
        val common = StorageAccess.COMMON_FOLDERS
        val labels = common.map { it.first }.toTypedArray() + "Choose another folder\u2026"
        AlertDialog.Builder(this)
            .setTitle("Add Folder")
            .setItems(labels) { _, which ->
                if (which < common.size) addCommonFolder(common[which].first, common[which].second)
                else folderPicker.launch(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Android 11+ won't let the system folder picker hand out the Downloads root, so
     * Downloads is watched as a plain path with All files access instead.
     */
    private fun addCommonFolder(label: String, dirType: String) {
        val uri = StorageAccess.publicDirUri(dirType)
        if (folders.any { it.localUri == uri }) {
            Toast.makeText(this, "$label is already watched", Toast.LENGTH_SHORT).show()
            return
        }
        if (StorageAccess.hasAllFilesAccess(this)) {
            addFolder(uri, label)
            return
        }
        pendingAdd = label to dirType
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("Allow access to $label")
                .setMessage("Android only lets backup apps read folders like Downloads, DCIM and Music with \"All files access\". On the next screen, turn on \"Allow access to manage all files\" for svn-space, then come back.")
                .setPositiveButton("Open settings") { _, _ -> openAllFilesAccessScreen() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> pendingAdd = null }
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
                pendingAdd = null
                Toast.makeText(this, "Open Settings → Apps → svn-space → All files access", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finishDownloadsAdd() {
        val wanted = pendingAdd
        pendingAdd = null
        adapter.notifyDataSetChanged()
        if (wanted == null) return
        if (StorageAccess.hasAllFilesAccess(this)) {
            val uri = StorageAccess.publicDirUri(wanted.second)
            if (folders.none { it.localUri == uri }) addFolder(uri, wanted.first)
        } else {
            Toast.makeText(this, "${wanted.first} needs All files access to be watched", Toast.LENGTH_LONG).show()
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
            pendingAdd = null
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
        // With several accounts and none chosen yet, start on a "choose" entry so saving
        // the dialog never quietly picks the first account for this folder.
        val needsChoice = conns.size > 1 && conns.none { it.id == folder.connectionId }
        val offset = if (needsChoice) 1 else 0
        if (conns.isEmpty()) {
            b.spinnerConnection.visibility = View.GONE
            b.txtNoConnections.visibility = View.VISIBLE
        } else {
            val labels = (if (needsChoice) listOf("Choose an account…") else emptyList()) +
                conns.map { it.displayName }
            b.spinnerConnection.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, labels
            )
            val idx = conns.indexOfFirst { it.id == folder.connectionId }
            b.spinnerConnection.setSelection(if (idx >= 0) idx + offset else 0)
        }

        // Destination folder in the cloud: picked from the provider's real folders.
        // null = automatic (Drive: svn-space/<folder name>; WebDAV: the server's base path).
        val savedConn = conns.firstOrNull { it.id == folder.connectionId }
        var chosenRemote: String? = folder.remotePath?.takeIf {
            // The worker treats "/" on Drive as automatic, so show it that way.
            it.isNotBlank() && !(it == "/" && savedConn?.type == ConnectionType.OAUTH_GOOGLE)
        }
        var chosenFor: String? = folder.connectionId
        fun selectedConn(): Connection? =
            if (conns.isEmpty()) null else conns.getOrNull(b.spinnerConnection.selectedItemPosition - offset)
        fun refreshRemote() {
            val c = selectedConn()
            if (c != null && chosenFor != null && c.id != chosenFor) {
                // A folder picked in one account doesn't exist in another: back to automatic.
                chosenRemote = null
            }
            chosenFor = c?.id ?: chosenFor
            val isDrive = c?.type == ConnectionType.OAUTH_GOOGLE
            val top = if (isDrive) "My Drive" else "Server"
            b.txtRemotePath.text = when {
                c == null -> "Choose an account first"
                chosenRemote != null -> top + chosenRemote!!.let { if (it == "/") " (top level)" else it.replace("/", " \u203a ") }
                isDrive -> "Automatic: My Drive" +
                    "${c.basePath ?: GoogleDriveClient.DEFAULT_ROOT}/${folder.displayPath}".replace("/", " \u203a ")
                else -> "Automatic: top folder of the server"
            }
            b.btnPickRemote.isEnabled = c != null
            b.btnAutoRemote.visibility = if (chosenRemote != null) View.VISIBLE else View.GONE
        }
        refreshRemote()
        b.spinnerConnection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refreshRemote()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        b.btnPickRemote.setOnClickListener {
            val c = selectedConn() ?: return@setOnClickListener
            RemoteFolderPicker(this, repo, c, chosenRemote) { picked ->
                chosenRemote = picked
                chosenFor = c.id
                refreshRemote()
            }.show()
        }
        b.btnAutoRemote.setOnClickListener {
            chosenRemote = null
            refreshRemote()
        }

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

        val intervalOptions = longArrayOf(0) + BackupScheduler.INTERVAL_MINUTES
        val intervalLabels = listOf("Every scheduled scan") + BackupScheduler.INTERVAL_LABELS.toList()
        b.spinnerInterval.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, intervalLabels
        )
        b.spinnerInterval.setSelection(
            intervalOptions.indexOf(StorageAccess.folderIntervalMinutes(this, folder.id)).coerceAtLeast(0)
        )
        b.switchLowBattery.isChecked = StorageAccess.pauseOnLowBattery(this, folder.id)

        val builder = AlertDialog.Builder(this)
            .setTitle("Advanced Settings \u00b7 ${folder.displayPath}")
            .setView(b.root)
            .setPositiveButton("Save") { _, _ ->
                val conn = if (conns.isEmpty()) null
                else conns.getOrNull(b.spinnerConnection.selectedItemPosition - offset)
                val filter = filters.getOrNull(b.spinnerFilter.selectedItemPosition) ?: FileFilter.ANY
                val exts = b.editExtensions.text?.toString().orEmpty()
                    .split(',', ' ', ';')
                    .map { it.trim().removePrefix(".").lowercase() }
                    .filter { it.isNotBlank() }
                    .distinct()
                val remote = chosenRemote.orEmpty()
                val updated = folder.copy(
                    connectionId = conn?.id ?: folder.connectionId,
                    remotePath = remote.ifBlank { null },
                    fileFilter = if (filter == FileFilter.CUSTOM && exts.isEmpty()) FileFilter.ANY else filter,
                    customExtensions = exts
                )
                StorageAccess.saveFolderOptions(
                    this,
                    folder.id,
                    intervalOptions.getOrElse(b.spinnerInterval.selectedItemPosition) { 0L },
                    b.switchLowBattery.isChecked
                )
                val oldConn = conns.firstOrNull { it.id == folder.connectionId }
                val newConn = conns.firstOrNull { it.id == updated.connectionId }
                lifecycleScope.launch {
                    repo.saveWatchedFolder(updated)
                    when {
                        newConn == null -> Toast.makeText(
                            this@WatchedFoldersActivity,
                            "Saved – this folder won't back up until you choose an account",
                            Toast.LENGTH_LONG
                        ).show()
                        folder.connectionId != null && folder.connectionId != newConn.id ->
                            AlertDialog.Builder(this@WatchedFoldersActivity)
                                .setTitle("Now backing up to ${newConn.displayName}")
                                .setMessage(
                                    "Copies already on ${oldConn?.displayName ?: "the old account"} stay there; " +
                                        "nothing is deleted or moved. The next scan uploads this folder's " +
                                        "files to ${newConn.displayName}, so that scan may take a while."
                                )
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        else -> Toast.makeText(this@WatchedFoldersActivity, "Saved", Toast.LENGTH_SHORT).show()
                    }
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

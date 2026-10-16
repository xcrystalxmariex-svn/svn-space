package space.svn.svnspace.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import space.svn.svnspace.data.model.WatchedFolder
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.databinding.ActivityWatchedFoldersBinding
import space.svn.svnspace.util.ThemeManager
import java.util.UUID

/**
 * Lists watched folders and lets the user add new ones via the system folder picker (SAF).
 * Grants persistable URI permission so the app can keep access across reboots.
 */
class WatchedFoldersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWatchedFoldersBinding
    private lateinit var repo: SvnRepository
    private lateinit var adapter: WatchedFolderAdapter

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // Take persistable permission
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val name = uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString()
        lifecycleScope.launch {
            val folder = WatchedFolder(
                id = UUID.randomUUID().toString(),
                localUri = uri.toString(),
                displayPath = name,
                isEnabled = true
            )
            repo.saveWatchedFolder(folder)
            Toast.makeText(this@WatchedFoldersActivity, "Added: $name", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.themeStyleRes(this))
        super.onCreate(savedInstanceState)
        binding = ActivityWatchedFoldersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Watched Folders"

        repo = SvnRepository(this)
        adapter = WatchedFolderAdapter(
            onToggle = { folder, enabled ->
                lifecycleScope.launch {
                    repo.saveWatchedFolder(folder.copy(isEnabled = enabled))
                }
            },
            onDelete = { folder ->
                lifecycleScope.launch {
                    repo.deleteWatchedFolder(folder.id)
                }
            }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fabAdd.setOnClickListener {
            folderPicker.launch(null)
        }

        lifecycleScope.launch {
            repo.observeWatchedFolders().collectLatest { list ->
                adapter.submitList(list)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

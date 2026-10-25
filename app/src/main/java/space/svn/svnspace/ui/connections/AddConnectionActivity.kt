package space.svn.svnspace.ui.connections

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import space.svn.svnspace.ui.common.ThemedActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.svn.svnspace.R
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.remote.WebDavAddress
import space.svn.svnspace.data.remote.WebDavClient
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.databinding.ActivityAddConnectionBinding
import space.svn.svnspace.util.ThemeManager
import java.util.UUID

class AddConnectionActivity : ThemedActivity() {

    private lateinit var binding: ActivityAddConnectionBinding
    private lateinit var repo: SvnRepository

    private val typeMap = listOf(
        "OAuth – Google Drive" to ConnectionType.OAUTH_GOOGLE,
        "OAuth – OneDrive" to ConnectionType.OAUTH_MICROSOFT,
        "OAuth – Dropbox" to ConnectionType.OAUTH_DROPBOX,
        "WebDAV" to ConnectionType.WEBDAV,
        "SFTP" to ConnectionType.SFTP,
        "FTP" to ConnectionType.FTP,
        "SMB / CIFS" to ConnectionType.SMB,
        "Custom / Other" to ConnectionType.CUSTOM_HTTP
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (intent.getBooleanExtra(EXTRA_CUSTOM, false)) {
            "Add Custom Provider"
        } else getString(R.string.add_connection_title)

        repo = SvnRepository(this)
        binding.spinnerType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            typeMap.map { it.first }
        )
        if (intent.getBooleanExtra(EXTRA_CUSTOM, false)) {
            binding.spinnerType.setSelection(typeMap.indexOfFirst { it.second == ConnectionType.WEBDAV })
        }
        binding.btnTest.setOnClickListener { testConnection() }

        binding.btnSave.setOnClickListener { saveCredentialConnection() }
        binding.btnOauth.setOnClickListener { launchOAuth() }
    }

    private fun saveCredentialConnection() {
        val idx = binding.spinnerType.selectedItemPosition
        val type = typeMap.getOrNull(idx)?.second ?: ConnectionType.OTHER
        if (type == ConnectionType.OAUTH_GOOGLE || type == ConnectionType.OAUTH_MICROSOFT || type == ConnectionType.OAUTH_DROPBOX) {
            Toast.makeText(this, "Use the OAuth button for provider connections", Toast.LENGTH_LONG).show()
            return
        }

        val name = binding.editDisplayName.text?.toString()?.ifBlank { "Unnamed" } ?: "Unnamed"
        val user = binding.editUsername.text?.toString().orEmpty()
        val pass = binding.editPassword.text?.toString().orEmpty()
        val host = binding.editHost.text?.toString().orEmpty().trim()
        val port = binding.editPort.text?.toString()?.trim()?.toIntOrNull()
        val path = binding.editPath.text?.toString()

        if (host.isBlank()) {
            Toast.makeText(this, "Host / URL is required", Toast.LENGTH_SHORT).show()
            return
        }

        val isHttp = type == ConnectionType.WEBDAV || type == ConnectionType.CUSTOM_HTTP
        if (!isHttp) {
            saveAndFinish(Connection(
                displayName = name, type = type, username = user, host = host, port = port,
                basePath = path?.ifBlank { null }, isOAuth = false, providerHint = type.name.lowercase()
            ), pass)
            return
        }

        binding.btnSave.isEnabled = false
        showTestResult("Checking the server before saving \u2026")
        lifecycleScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) { WebDavClient.testAddress(host, port, path, user, pass) }
            } catch (e: WebDavAddress.BadAddressException) {
                binding.btnSave.isEnabled = true
                showTestResult("\u2717 ${e.message}")
                return@launch
            }
            binding.btnSave.isEnabled = true
            // Store the server address with the scheme that worked (or the best guess), and the
            // Path field on its own; WebDavClient.forConnection joins them the same way everywhere.
            val conn = Connection(
                id = UUID.randomUUID().toString(),
                displayName = name,
                type = type,
                username = user,
                host = WebDavAddress.server(host, port, forceScheme = outcome.scheme),
                port = port,
                basePath = WebDavAddress.normalizePath(path),
                useTls = outcome.scheme == "https",
                isOAuth = false,
                providerHint = type.name.lowercase(),
                lastVerifiedAt = if (outcome.ok) System.currentTimeMillis() else null
            )
            if (outcome.ok) {
                showTestResult("\u2713 Connected to ${outcome.url}")
                saveAndFinish(conn, pass)
            } else {
                showTestResult("\u2717 Couldn't connect to ${outcome.url}: ${outcome.error}")
                AlertDialog.Builder(this@AddConnectionActivity)
                    .setTitle("Couldn't connect")
                    .setMessage("${outcome.error}\n\nSave this connection anyway?")
                    .setPositiveButton("Save anyway") { _, _ -> saveAndFinish(conn, pass) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun saveAndFinish(conn: Connection, pass: String) {
        lifecycleScope.launch {
            repo.saveConnection(conn, pass)
            Toast.makeText(this@AddConnectionActivity, "Connection saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** Network test off the main thread, with the Path field and the real error. */
    private fun testConnection() {
        val idx = binding.spinnerType.selectedItemPosition
        val type = typeMap.getOrNull(idx)?.second ?: ConnectionType.OTHER
        val host = binding.editHost.text?.toString().orEmpty().trim()
        if (host.isBlank()) {
            showTestResult("Enter the server address first")
            return
        }
        if (type != ConnectionType.WEBDAV && type != ConnectionType.CUSTOM_HTTP) {
            showTestResult("Testing works for WebDAV and custom HTTP servers")
            return
        }
        val port = binding.editPort.text?.toString()?.trim()?.toIntOrNull()
        val path = binding.editPath.text?.toString()
        val user = binding.editUsername.text?.toString().orEmpty()
        val pass = binding.editPassword.text?.toString().orEmpty()
        val first = try {
            WebDavAddress.endpoint(host, port, path)
        } catch (e: WebDavAddress.BadAddressException) {
            showTestResult("\u2717 ${e.message}")
            return
        }
        binding.btnTest.isEnabled = false
        showTestResult("Testing $first \u2026")
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    WebDavClient.testAddress(host, port, path, user, pass)
                } catch (e: Exception) {
                    WebDavClient.TestOutcome(first, e.message ?: e.javaClass.simpleName)
                }
            }
            binding.btnTest.isEnabled = true
            if (outcome.ok) {
                // The guess was wrong but the other scheme worked: show it in the field, so
                // what gets saved is what was tested.
                if (!WebDavAddress.hasExplicitScheme(host) && !outcome.url.startsWith(first.substringBefore("://"))) {
                    binding.editHost.setText("${outcome.scheme}://$host")
                }
                showTestResult("\u2713 Connected to ${outcome.url}")
            } else {
                showTestResult("\u2717 Couldn't connect to ${outcome.url}: ${outcome.error}")
            }
        }
    }

    private fun showTestResult(text: String) {
        binding.txtTestResult.visibility = android.view.View.VISIBLE
        binding.txtTestResult.text = text
    }

    private fun launchOAuth() {
        val idx = binding.spinnerType.selectedItemPosition
        val type = typeMap.getOrNull(idx)?.second
        when (type) {
            ConnectionType.OAUTH_GOOGLE -> OAuthHelper.launchGoogle(this)
            ConnectionType.OAUTH_MICROSOFT -> OAuthHelper.launchMicrosoft(this)
            ConnectionType.OAUTH_DROPBOX -> OAuthHelper.launchDropbox(this)
            else -> Toast.makeText(this, "Select an OAuth provider type first", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_CUSTOM = "custom_provider"
    }
}

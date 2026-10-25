package space.svn.svnspace.ui.connections

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.svn.svnspace.R
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.remote.WebDavClient
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.databinding.ActivityAddConnectionBinding
import space.svn.svnspace.util.ThemeManager
import java.util.UUID

class AddConnectionActivity : AppCompatActivity() {

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
        ThemeManager.applyToActivity(this)
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
        val host = binding.editHost.text?.toString().orEmpty()
        val port = binding.editPort.text?.toString()?.toIntOrNull()
        val path = binding.editPath.text?.toString()

        if (host.isBlank()) {
            Toast.makeText(this, "Host / URL is required", Toast.LENGTH_SHORT).show()
            return
        }

        val isHttp = type == ConnectionType.WEBDAV || type == ConnectionType.CUSTOM_HTTP
        val conn = Connection(
            id = UUID.randomUUID().toString(),
            displayName = name,
            type = type,
            username = user,
            // Backups use the stored address as-is, so fold the port in here.
            host = if (isHttp) effectiveUrl(host, port) else host,
            port = port,
            basePath = path,
            isOAuth = false,
            providerHint = type.name.lowercase()
        )

        lifecycleScope.launch {
            if (isHttp) {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        WebDavClient(effectiveUrl(host, port), user, pass).testConnection()
                    } catch (e: Exception) {
                        false
                    }
                }
                if (!ok) {
                    Toast.makeText(this@AddConnectionActivity, "Could not reach server – saved anyway", Toast.LENGTH_LONG).show()
                }
            }
            repo.saveConnection(conn, pass)
            Toast.makeText(this@AddConnectionActivity, "Connection saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** Network test off the main thread (it used to run on it and always fail). */
    private fun testConnection() {
        val idx = binding.spinnerType.selectedItemPosition
        val type = typeMap.getOrNull(idx)?.second ?: ConnectionType.OTHER
        val host = binding.editHost.text?.toString().orEmpty()
        if (host.isBlank()) {
            showTestResult("Enter the server address first")
            return
        }
        if (type != ConnectionType.WEBDAV && type != ConnectionType.CUSTOM_HTTP) {
            showTestResult("Testing works for WebDAV and custom HTTP servers")
            return
        }
        val url = effectiveUrl(host, binding.editPort.text?.toString()?.toIntOrNull())
        val user = binding.editUsername.text?.toString().orEmpty()
        val pass = binding.editPassword.text?.toString().orEmpty()
        binding.btnTest.isEnabled = false
        showTestResult("Testing $url \u2026")
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    if (WebDavClient(url, user, pass).testConnection()) null else "server answered, but not as WebDAV (check the path, username and password)"
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            binding.btnTest.isEnabled = true
            showTestResult(if (error == null) "\u2713 Connected to $url" else "\u2717 Couldn't connect: $error")
        }
    }

    private fun showTestResult(text: String) {
        binding.txtTestResult.visibility = android.view.View.VISIBLE
        binding.txtTestResult.text = text
    }

    /** Adds https:// when no scheme is given and the port when one is typed. */
    private fun effectiveUrl(host: String, port: Int?): String {
        var url = host.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        if (port != null) {
            val uri = android.net.Uri.parse(url)
            if (uri.port == -1 && uri.host != null) {
                url = url.replaceFirst(uri.host!!, "${uri.host}:$port")
            }
        }
        return url
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

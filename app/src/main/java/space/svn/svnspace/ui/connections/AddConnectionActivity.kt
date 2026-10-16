package space.svn.svnspace.ui.connections

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
        setTheme(ThemeManager.themeStyleRes(this))
        super.onCreate(savedInstanceState)
        binding = ActivityAddConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.add_connection_title)

        repo = SvnRepository(this)
        binding.spinnerType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            typeMap.map { it.first }
        )

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

        val conn = Connection(
            id = UUID.randomUUID().toString(),
            displayName = name,
            type = type,
            username = user,
            host = host,
            port = port,
            basePath = path,
            isOAuth = false,
            providerHint = type.name.lowercase()
        )

        lifecycleScope.launch {
            if (type == ConnectionType.WEBDAV || type == ConnectionType.CUSTOM_HTTP) {
                val ok = try {
                    WebDavClient(host, user, pass).testConnection()
                } catch (e: Exception) {
                    false
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
}

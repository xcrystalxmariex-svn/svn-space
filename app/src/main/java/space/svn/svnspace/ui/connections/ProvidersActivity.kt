package space.svn.svnspace.ui.connections

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import space.svn.svnspace.ui.common.ThemedActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.svn.svnspace.data.remote.GoogleDriveClient
import space.svn.svnspace.R
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.databinding.ActivityProvidersBinding
import space.svn.svnspace.databinding.ItemWatchedFolderBinding
import space.svn.svnspace.ui.browser.CloudBrowserActivity
import space.svn.svnspace.util.AuthState

/** Cloud Accounts / All Providers: every connection with an on/off toggle. */
class ProvidersActivity : ThemedActivity() {

    private lateinit var binding: ActivityProvidersBinding
    private lateinit var repo: SvnRepository
    private var items: List<Pair<Connection, Boolean>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProvidersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Cloud Accounts"

        repo = SvnRepository(this)
        val adapter = Adapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnGoogle.setOnClickListener { OAuthHelper.launchGoogle(this) }
        binding.btnCustom.setOnClickListener {
            startActivity(
                Intent(this, AddConnectionActivity::class.java)
                    .putExtra(AddConnectionActivity.EXTRA_CUSTOM, true)
            )
        }

        lifecycleScope.launch {
            repo.observeConnections().collect { list ->
                // Pair each connection with whether it still has a saved sign-in.
                items = list.map { it to (repo.getSecret(it.id) != null) }
                binding.txtEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun label(c: Connection): String = when (c.type) {
        ConnectionType.OAUTH_GOOGLE -> "Google Drive"
        ConnectionType.OAUTH_MICROSOFT -> "OneDrive"
        ConnectionType.OAUTH_DROPBOX -> "Dropbox"
        ConnectionType.WEBDAV -> "WebDAV"
        ConnectionType.CUSTOM_HTTP -> "Custom HTTP"
        else -> c.type.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val b: ItemWatchedFolderBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemWatchedFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (conn, signedIn) = items[position]
            val b = holder.b
            b.txtPath.text = conn.displayName
            val reason = AuthState.reasonFor(this@ProvidersActivity, conn.id)
                ?: if (!signedIn) AuthState.Reason.MISSING_LOGIN else null
            val status = when {
                reason != null -> AuthState.label(reason)
                conn.isEnabled -> "Connected · tap to browse"
                else -> "Paused · tap to browse"
            }
            b.txtSummary.text = "${label(conn)} \u00b7 $status"
            b.switchEnabled.setOnCheckedChangeListener(null)
            b.switchEnabled.isChecked = conn.isEnabled
            b.switchEnabled.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch { repo.saveConnection(conn.copy(isEnabled = checked)) }
            }
            b.btnDelete.setOnClickListener {
                AlertDialog.Builder(this@ProvidersActivity)
                    .setTitle("Remove ${conn.displayName}?")
                    .setMessage("Its sign-in is deleted from this phone. Files already in the cloud stay there.")
                    .setPositiveButton("Remove") { _, _ ->
                        lifecycleScope.launch { repo.deleteConnection(conn.id) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            b.root.setOnClickListener {
                if (reason != null) {
                    Reconnect.start(this@ProvidersActivity, lifecycleScope, repo, conn) { notifyDataSetChanged() }
                } else {
                    startActivity(CloudBrowserActivity.intent(this@ProvidersActivity, conn.id))
                }
            }
            b.root.setOnLongClickListener {
                AlertDialog.Builder(this@ProvidersActivity)
                    .setTitle(conn.displayName)
                    .setItems(arrayOf("Browse files", "Reconnect")) { _, which ->
                        when (which) {
                            0 -> startActivity(CloudBrowserActivity.intent(this@ProvidersActivity, conn.id))
                            1 -> Reconnect.start(this@ProvidersActivity, lifecycleScope, repo, conn) { notifyDataSetChanged() }
                        }
                    }
                    .show()
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reconnect state lives outside the database, so refresh the rows when coming back.
        binding.recycler.adapter?.notifyDataSetChanged()
        checkGoogleScopes()
    }

    /**
     * Google logins from before the full-Drive change only see files the app made, and
     * Google gives no error for that. Check each one so its row says "Limited access".
     */
    private fun checkGoogleScopes() {
        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) {
                var any = false
                repo.observeConnections().first()
                    .filter { it.type == ConnectionType.OAUTH_GOOGLE }
                    .forEach { c ->
                        val secret = repo.getSecret(c.id) ?: return@forEach
                        val before = AuthState.reasonFor(this@ProvidersActivity, c.id)
                        val full = try {
                            GoogleDriveClient(OAuthHelper.GOOGLE_CLIENT_ID, secret).grantedFullDrive()
                        } catch (e: GoogleDriveClient.ReauthRequiredException) {
                            AuthState.markNeedsReconnect(this@ProvidersActivity, c.id, AuthState.Reason.EXPIRED)
                            any = any || before != AuthState.Reason.EXPIRED
                            return@forEach
                        }
                        if (full == false && before != AuthState.Reason.LIMITED_SCOPE) {
                            AuthState.markNeedsReconnect(this@ProvidersActivity, c.id, AuthState.Reason.LIMITED_SCOPE)
                            any = true
                        } else if (full == true && before == AuthState.Reason.LIMITED_SCOPE) {
                            AuthState.clear(this@ProvidersActivity, c.id)
                            any = true
                        }
                    }
                any
            }
            if (changed) binding.recycler.adapter?.notifyDataSetChanged()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

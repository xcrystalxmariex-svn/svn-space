package space.svn.svnspace.ui.connections

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.remote.WebDavClient
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.util.AuthState

/** Signs an existing account in again without losing its folders and settings. */
object Reconnect {

    fun start(activity: Activity, scope: LifecycleCoroutineScope, repo: SvnRepository, conn: Connection, onDone: () -> Unit = {}) {
        when (conn.type) {
            ConnectionType.OAUTH_GOOGLE ->
                // Google's account chooser opens with this account suggested; the result
                // updates this same connection (OAuthRedirectActivity, EXTRA_RECONNECT_ID).
                OAuthHelper.launchGoogle(activity, reconnectId = conn.id, loginHint = conn.username)
            ConnectionType.WEBDAV, ConnectionType.CUSTOM_HTTP -> askPassword(activity, scope, repo, conn, onDone)
            else -> Toast.makeText(activity, "Reconnecting this kind of account isn't supported yet", Toast.LENGTH_LONG).show()
        }
    }

    private fun askPassword(activity: Activity, scope: LifecycleCoroutineScope, repo: SvnRepository, conn: Connection, onDone: () -> Unit) {
        val input = EditText(activity).apply {
            hint = "Password for ${conn.username ?: "this server"}"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(activity)
            .setTitle("Reconnect ${conn.displayName}")
            .setMessage(conn.host)
            .setView(frame)
            .setPositiveButton("Connect") { _, _ ->
                val password = input.text.toString()
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            WebDavClient(conn.host ?: "", conn.username ?: "", password).list(conn.basePath ?: "/")
                            true
                        } catch (e: Exception) {
                            false
                        }
                    }
                    if (ok) {
                        withContext(Dispatchers.IO) { repo.saveConnection(conn.copy(isEnabled = true), password) }
                        AuthState.clear(activity, conn.id)
                        Toast.makeText(activity, "${conn.displayName} reconnected", Toast.LENGTH_SHORT).show()
                        onDone()
                    } else {
                        Toast.makeText(activity, "Couldn't sign in. Check the password and try again.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

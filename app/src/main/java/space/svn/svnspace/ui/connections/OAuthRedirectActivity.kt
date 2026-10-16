package space.svn.svnspace.ui.connections

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.NoClientAuthentication
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.util.SecureStore
import java.util.UUID

/**
 * Receives svnspace://oauth redirect, exchanges the code for tokens
 * (public / installed client — no secret), and saves a Google Drive connection.
 * Each successful flow creates a new Connection, so multiple Google accounts work.
 */
class OAuthRedirectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val response = AuthorizationResponse.fromIntent(intent)
        val ex = AuthorizationException.fromIntent(intent)

        when {
            response != null -> {
                Log.i(TAG, "Authorization code received – exchanging for tokens")
                exchangeAndSave(response)
            }
            ex != null -> {
                Log.e(TAG, "OAuth error: ${ex.error} – ${ex.errorDescription}")
                Toast.makeText(this, "OAuth failed: ${ex.errorDescription ?: ex.error}", Toast.LENGTH_LONG).show()
                finish()
            }
            else -> {
                Log.w(TAG, "OAuth redirect with nothing usable")
                finish()
            }
        }
    }

    private fun exchangeAndSave(response: AuthorizationResponse) {
        val service = AuthorizationService(this)
        // Installed / public client — no client secret
        val clientAuth: ClientAuthentication = NoClientAuthentication.INSTANCE
        val tokenRequest = response.createTokenExchangeRequest()

        service.performTokenRequest(tokenRequest, clientAuth) { tokenResponse, exception ->
            if (tokenResponse != null) {
                val access = tokenResponse.accessToken
                val refresh = tokenResponse.refreshToken
                Log.i(TAG, "Token exchange OK – access=${access?.take(12)}… refresh=${refresh != null}")

                lifecycleScope.launch {
                    val repo = SvnRepository(this@OAuthRedirectActivity)
                    val conn = Connection(
                        id = UUID.randomUUID().toString(),
                        displayName = "Google Drive",
                        type = ConnectionType.OAUTH_GOOGLE,
                        isOAuth = true,
                        providerHint = "google",
                        host = "https://www.googleapis.com/drive/v3"
                    )
                    // Prefer refresh token for long-lived access
                    val secretToStore = refresh ?: access ?: ""
                    repo.saveConnection(conn, secretToStore)

                    if (access != null) {
                        SecureStore(this@OAuthRedirectActivity)
                            .putSecret("conn_${conn.id}_access", access)
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@OAuthRedirectActivity,
                            "Google Drive connected",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }
            } else {
                Log.e(TAG, "Token exchange failed", exception)
                Toast.makeText(
                    this,
                    "Token exchange failed: ${exception?.errorDescription ?: exception?.message}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "OAuthRedirect"
    }
}

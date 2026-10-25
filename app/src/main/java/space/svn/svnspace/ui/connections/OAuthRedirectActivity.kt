package space.svn.svnspace.ui.connections

import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.NoClientAuthentication
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.remote.GoogleDriveClient
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.util.SecureStore
import space.svn.svnspace.util.ThemeManager
import java.util.UUID

/**
 * Gets the result of the Google sign-in from AppAuth (via the completion PendingIntent
 * set in OAuthHelper.launchGoogle), trades the code for tokens (public client, PKCE,
 * no secret) and saves one Google Drive connection per Google account.
 *
 * Signing in again with an account that is already connected refreshes that
 * connection's login instead of adding a duplicate.
 */
class OAuthRedirectActivity : AppCompatActivity() {

    private var service: AuthorizationService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyToActivity(this)
        super.onCreate(savedInstanceState)

        if (intent.getBooleanExtra(EXTRA_CANCELLED, false)) {
            Toast.makeText(this, "Google sign-in cancelled", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val response = AuthorizationResponse.fromIntent(intent)
        val ex = AuthorizationException.fromIntent(intent)

        when {
            response != null -> exchangeAndSave(response)
            ex != null -> {
                Log.e(TAG, "OAuth error: ${ex.error} – ${ex.errorDescription}")
                Toast.makeText(this, "Google sign-in failed: ${ex.errorDescription ?: ex.error}", Toast.LENGTH_LONG).show()
                finish()
            }
            else -> {
                Log.w(TAG, "OAuth result with nothing usable")
                finish()
            }
        }
    }

    private fun exchangeAndSave(response: AuthorizationResponse) {
        val svc = AuthorizationService(this).also { service = it }
        svc.performTokenRequest(
            response.createTokenExchangeRequest(),
            NoClientAuthentication.INSTANCE
        ) { tokenResponse, exception ->
            if (tokenResponse == null) {
                Log.e(TAG, "Token exchange failed", exception)
                Toast.makeText(
                    this,
                    "Token exchange failed: ${exception?.errorDescription ?: exception?.message}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@performTokenRequest
            }

            val refresh = tokenResponse.refreshToken
            if (refresh.isNullOrBlank()) {
                Toast.makeText(this, "Google didn't return a refresh token – try connecting again", Toast.LENGTH_LONG).show()
                finish()
                return@performTokenRequest
            }
            val email = emailFromIdToken(tokenResponse.idToken)

            lifecycleScope.launch {
                val repo = SvnRepository(this@OAuthRedirectActivity)
                val existing = if (email != null) {
                    withContext(Dispatchers.IO) {
                        repo.getEnabledConnections().firstOrNull {
                            it.type == ConnectionType.OAUTH_GOOGLE && it.username.equals(email, ignoreCase = true)
                        }
                    }
                } else null

                val conn = (existing ?: Connection(
                    id = UUID.randomUUID().toString(),
                    displayName = if (email != null) "Google Drive · $email" else "Google Drive",
                    type = ConnectionType.OAUTH_GOOGLE,
                    isOAuth = true,
                    providerHint = "google",
                    username = email,
                    host = GoogleDriveClient.API,
                    basePath = GoogleDriveClient.DEFAULT_ROOT
                )).copy(lastVerifiedAt = System.currentTimeMillis())

                withContext(Dispatchers.IO) {
                    repo.saveConnection(conn, refresh)
                    tokenResponse.accessToken?.let {
                        SecureStore(this@OAuthRedirectActivity).putSecret("conn_${conn.id}_access", it)
                    }
                }
                Toast.makeText(
                    this@OAuthRedirectActivity,
                    if (existing != null) "Google Drive login refreshed for $email"
                    else "Google Drive connected${email?.let { " ($it)" } ?: ""}",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    /** Reads the email claim from Google's ID token (already checked by AppAuth over TLS). */
    private fun emailFromIdToken(idToken: String?): String? = try {
        val payload = idToken?.split('.')?.getOrNull(1) ?: return null
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        JsonParser.parseString(json).asJsonObject.get("email")?.asString
    } catch (e: Exception) {
        null
    }

    override fun onDestroy() {
        service?.dispose()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OAuthRedirect"
        const val EXTRA_PROVIDER = "svn_oauth_provider"
        const val EXTRA_CANCELLED = "svn_oauth_cancelled"
    }
}

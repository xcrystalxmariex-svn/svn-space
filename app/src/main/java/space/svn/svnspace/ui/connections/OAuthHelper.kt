package space.svn.svnspace.ui.connections

import android.content.Context
import android.net.Uri
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

/**
 * OAuth launch helpers.
 *
 * Google: installed (public) client — correct type for APK.
 * No client secret. Redirect: svnspace://oauth
 *
 * In Google Cloud Console for this client, under Authorized redirect URIs add:
 *   svnspace://oauth
 * (Installed/Desktop clients accept custom schemes more readily than Web clients.)
 */
object OAuthHelper {

    // ── Google (installed / APK client) ──────────────────────────
    const val GOOGLE_CLIENT_ID =
        "606145683240-9admvfv6f4vuchepn0fidbhuk95phufn.apps.googleusercontent.com"
    // No secret — public client
    // ─────────────────────────────────────────────────────────────

    const val MICROSOFT_CLIENT_ID = "YOUR_MICROSOFT_CLIENT_ID"
    const val DROPBOX_CLIENT_ID = "YOUR_DROPBOX_APP_KEY"

    val REDIRECT: Uri = Uri.parse("svnspace://oauth")

    fun launchGoogle(context: Context) {
        val config = AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
            Uri.parse("https://oauth2.googleapis.com/token")
        )
        val request = AuthorizationRequest.Builder(
            config,
            GOOGLE_CLIENT_ID,
            ResponseTypeValues.CODE,
            REDIRECT
        )
            .setScopes(
                "https://www.googleapis.com/auth/drive.file",
                "https://www.googleapis.com/auth/drive.appdata"
            )
            .setPrompt("consent") // force refresh_token on first grant
            .build()

        val service = AuthorizationService(context)
        context.startActivity(service.getAuthorizationRequestIntent(request))
    }

    fun launchMicrosoft(context: Context) {
        val config = AuthorizationServiceConfiguration(
            Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"),
            Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/token")
        )
        val request = AuthorizationRequest.Builder(
            config,
            MICROSOFT_CLIENT_ID,
            ResponseTypeValues.CODE,
            REDIRECT
        )
            .setScopes("Files.ReadWrite.All", "offline_access")
            .build()
        val service = AuthorizationService(context)
        context.startActivity(service.getAuthorizationRequestIntent(request))
    }

    fun launchDropbox(context: Context) {
        val config = AuthorizationServiceConfiguration(
            Uri.parse("https://www.dropbox.com/oauth2/authorize"),
            Uri.parse("https://api.dropboxapi.com/oauth2/token")
        )
        val request = AuthorizationRequest.Builder(
            config,
            DROPBOX_CLIENT_ID,
            ResponseTypeValues.CODE,
            REDIRECT
        ).build()
        val service = AuthorizationService(context)
        context.startActivity(service.getAuthorizationRequestIntent(request))
    }
}

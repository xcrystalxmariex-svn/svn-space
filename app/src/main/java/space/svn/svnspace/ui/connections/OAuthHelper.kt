package space.svn.svnspace.ui.connections

import android.content.Context
import android.net.Uri
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import space.svn.svnspace.BuildConfig

/**
 * OAuth launch helpers.
 *
 * Redirect uses applicationId as scheme (package-style URI):
 *   space.svn.svnspace:/oauth2redirect
 *   space.svn.svnspace.debug:/oauth2redirect
 *
 * Register BOTH exact strings under Authorized redirect URIs on the Google OAuth client.
 * Manifest scheme comes from manifestPlaceholders["appAuthRedirectScheme"].
 */
object OAuthHelper {

    const val GOOGLE_CLIENT_ID =
        "606145683240-9admvfv6f4vuchepn0fidbhuk95phufn.apps.googleusercontent.com"

    const val MICROSOFT_CLIENT_ID = "YOUR_MICROSOFT_CLIENT_ID"
    const val DROPBOX_CLIENT_ID = "YOUR_DROPBOX_APP_KEY"

    val REDIRECT: Uri
        get() = Uri.parse("${BuildConfig.APPLICATION_ID}:/oauth2redirect")

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
            .setPrompt("consent")
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

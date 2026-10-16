package space.svn.svnspace.ui.connections

import android.content.Context
import android.net.Uri
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

/**
 * Google installed client uses the reverse-client-id redirect form:
 *   com.googleusercontent.apps.{CLIENT_ID_PREFIX}:/oauth2redirect
 *
 * That is what Google Console registers for native/Desktop/Installed clients
 * when custom package URIs are not the path you configured.
 *
 * Client ID:
 *   606145683240-9admvfv6f4vuchepn0fidbhuk95phufn.apps.googleusercontent.com
 * Prefix (before .apps.googleusercontent.com):
 *   606145683240-9admvfv6f4vuchepn0fidbhuk95phufn
 */
object OAuthHelper {

    const val GOOGLE_CLIENT_ID =
        "606145683240-9admvfv6f4vuchepn0fidbhuk95phufn.apps.googleusercontent.com"

    /** Prefix only — used to build the reverse scheme. */
    private const val GOOGLE_CLIENT_ID_PREFIX =
        "606145683240-9admvfv6f4vuchepn0fidbhuk95phufn"

    /**
     * Exact redirect Google expects for this installed client:
     * com.googleusercontent.apps.606145683240-9admvfv6f4vuchepn0fidbhuk95phufn:/oauth2redirect
     */
    val REDIRECT: Uri = Uri.parse(
        "com.googleusercontent.apps.${GOOGLE_CLIENT_ID_PREFIX}:/oauth2redirect"
    )

    const val MICROSOFT_CLIENT_ID = "YOUR_MICROSOFT_CLIENT_ID"
    const val DROPBOX_CLIENT_ID = "YOUR_DROPBOX_APP_KEY"

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

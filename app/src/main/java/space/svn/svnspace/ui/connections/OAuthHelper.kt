package space.svn.svnspace.ui.connections

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

/**
 * Google: Android OAuth client in Google Cloud project "svn-space", with
 * "Enable custom URI scheme" turned on (Advanced settings), so it accepts the
 * reverse-client-id redirect:
 *   com.googleusercontent.apps.{CLIENT_ID_PREFIX}:/oauth2redirect
 * Public client: PKCE, no client secret. The same scheme is set in
 * app/build.gradle.kts as manifestPlaceholders["appAuthRedirectScheme"].
 *
 * Client ID:
 *   606145683240-9admvfv6f4vuchepn0fidbhuk95phufn.apps.googleusercontent.com
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

    /**
     * Google Drive scopes. The full "drive" scope lets the app browse and manage everything
     * in the account, like the Drive app itself. It's a restricted scope: while the Cloud
     * project is in Testing mode it works for listed test users without Google verification,
     * but Google expires those sign-ins after 7 days, and the app then asks to reconnect.
     * drive.appdata is kept for the app's hidden settings space.
     */
    val GOOGLE_SCOPES = listOf(
        "openid",
        "email",
        "https://www.googleapis.com/auth/drive",
        "https://www.googleapis.com/auth/drive.appdata"
    )

    /**
     * Starts Google sign-in in a Custom Tab. When Google redirects back, AppAuth's own
     * RedirectUriReceiverActivity catches the reverse-client-id redirect and hands the
     * result to OAuthRedirectActivity through the completion PendingIntent below.
     * Each run can pick a different Google account, so every account becomes its own connection.
     */
    fun launchGoogle(context: Context, reconnectId: String? = null, loginHint: String? = null) {
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
            .setScopes(GOOGLE_SCOPES)
            // Always show the account chooser (many Google accounts) and the consent
            // screen, so Google returns a refresh token every time.
            .setPromptValues("select_account", "consent")
            .apply { if (!loginHint.isNullOrBlank()) setLoginHint(loginHint) }
            .build()

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val complete = PendingIntent.getActivity(
            context, 1001,
            Intent(context, OAuthRedirectActivity::class.java)
                .putExtra(OAuthRedirectActivity.EXTRA_PROVIDER, "google")
                .putExtra(OAuthRedirectActivity.EXTRA_RECONNECT_ID, reconnectId),
            flags
        )
        val cancel = PendingIntent.getActivity(
            context, 1002,
            Intent(context, OAuthRedirectActivity::class.java)
                .putExtra(OAuthRedirectActivity.EXTRA_PROVIDER, "google")
                .putExtra(OAuthRedirectActivity.EXTRA_CANCELLED, true),
            flags
        )
        val service = AuthorizationService(context)
        service.performAuthorizationRequest(request, complete, cancel)
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

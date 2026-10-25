package space.svn.svnspace.util

import android.content.Context

/**
 * Remembers which connections need the user to sign in again, and why, so every
 * screen (Cloud Accounts, Files, the cloud browser, the dashboard) can say so
 * instead of failing silently. Cleared when the account is connected again.
 */
object AuthState {
    private const val PREFS = "auth_state"

    enum class Reason { EXPIRED, LIMITED_SCOPE, MISSING_LOGIN }

    fun markNeedsReconnect(context: Context, connectionId: String, reason: Reason) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(connectionId, reason.name).apply()
    }

    fun clear(context: Context, connectionId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(connectionId).apply()
    }

    fun reasonFor(context: Context, connectionId: String): Reason? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(connectionId, null)
            ?.let { runCatching { Reason.valueOf(it) }.getOrNull() }

    fun label(reason: Reason): String = when (reason) {
        Reason.EXPIRED -> "Sign-in expired – tap Reconnect"
        Reason.LIMITED_SCOPE -> "Limited access – tap to reconnect and see all files"
        Reason.MISSING_LOGIN -> "Login needed – tap Reconnect"
    }
}

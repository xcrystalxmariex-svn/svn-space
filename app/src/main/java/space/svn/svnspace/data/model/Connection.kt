package space.svn.svnspace.data.model

import java.util.UUID

/**
 * Unified connection model.
 * Supports both OAuth providers and direct credential-based remotes.
 */
data class Connection(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val type: ConnectionType,
    val username: String? = null,
    // Password / app-password / token material is stored encrypted separately
    val host: String? = null,          // URL, IP, or hostname
    val port: Int? = null,
    val basePath: String? = null,      // optional root path on remote
    val useTls: Boolean = true,
    val isOAuth: Boolean = false,
    val providerHint: String? = null,  // "google", "microsoft", "dropbox", "terrabox", "custom"
    val createdAt: Long = System.currentTimeMillis(),
    val lastVerifiedAt: Long? = null,
    val isEnabled: Boolean = true
)

enum class ConnectionType {
    OAUTH_GOOGLE,
    OAUTH_MICROSOFT,
    OAUTH_DROPBOX,
    WEBDAV,
    SFTP,
    FTP,
    SMB,
    CUSTOM_HTTP,
    OTHER
}

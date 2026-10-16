package space.svn.svnspace.data.model

import java.util.UUID

/**
 * Tracks a file that has been successfully backed up.
 * Used by "Free up space" to know what is safe to delete locally.
 */
data class BackupRecord(
    val id: String = UUID.randomUUID().toString(),
    val localUri: String,
    val localPath: String,
    val remoteConnectionId: String,
    val remotePath: String,
    val contentHash: String,          // simple checksum or size+mtime composite
    val sizeBytes: Long,
    val backedUpAt: Long = System.currentTimeMillis(),
    val verified: Boolean = true      // only verified records are eligible for free-up
)

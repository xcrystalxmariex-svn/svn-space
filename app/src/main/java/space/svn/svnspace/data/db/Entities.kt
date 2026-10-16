package space.svn.svnspace.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.model.FileFilter

@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val type: String,                 // ConnectionType name
    val username: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val basePath: String? = null,
    val useTls: Boolean = true,
    val isOAuth: Boolean = false,
    val providerHint: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastVerifiedAt: Long? = null,
    val isEnabled: Boolean = true
)

@Entity(tableName = "watched_folders")
data class WatchedFolderEntity(
    @PrimaryKey val id: String,
    val localUri: String,
    val displayPath: String,
    val isEnabled: Boolean = true,
    val connectionId: String? = null,
    val remotePath: String? = null,
    val fileFilter: String = FileFilter.ANY.name,
    val customExtensions: String = "",   // comma-separated
    val lastScanAt: Long? = null,
    val lastBackupAt: Long? = null
)

@Entity(tableName = "backup_records")
data class BackupRecordEntity(
    @PrimaryKey val id: String,
    val localUri: String,
    val localPath: String,
    val remoteConnectionId: String,
    val remotePath: String,
    val contentHash: String,
    val sizeBytes: Long,
    val backedUpAt: Long = System.currentTimeMillis(),
    val verified: Boolean = true
)

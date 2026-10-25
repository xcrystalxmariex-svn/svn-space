package space.svn.svnspace.data.repository

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.svn.svnspace.data.db.AppDatabase
import space.svn.svnspace.data.db.BackupRecordEntity
import space.svn.svnspace.data.db.ConnectionEntity
import space.svn.svnspace.data.db.WatchedFolderEntity
import space.svn.svnspace.data.model.BackupRecord
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.model.FileFilter
import space.svn.svnspace.data.model.WatchedFolder
import space.svn.svnspace.util.SecureStore
import java.util.UUID

class SvnRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val secure = SecureStore(context)

    // ── Connections ──────────────────────────────────────────────

    fun observeConnections(): Flow<List<Connection>> =
        db.connectionDao().observeAll().map { list -> list.map { it.toModel() } }

    suspend fun getConnection(id: String): Connection? =
        db.connectionDao().getById(id)?.toModel()

    suspend fun saveConnection(conn: Connection, passwordOrToken: String? = null) {
        db.connectionDao().upsert(conn.toEntity())
        if (passwordOrToken != null) {
            secure.putSecret("conn_${conn.id}", passwordOrToken)
        }
    }

    suspend fun getSecret(connectionId: String): String? =
        secure.getSecret("conn_$connectionId")

    suspend fun deleteConnection(id: String) {
        db.connectionDao().delete(id)
        secure.removeSecret("conn_$id")
    }

    suspend fun getEnabledConnections(): List<Connection> =
        db.connectionDao().getEnabled().map { it.toModel() }

    // ── Watched Folders ──────────────────────────────────────────

    fun observeWatchedFolders(): Flow<List<WatchedFolder>> =
        db.watchedFolderDao().observeAll().map { list -> list.map { it.toModel() } }

    suspend fun getEnabledWatchedFolders(): List<WatchedFolder> =
        db.watchedFolderDao().getEnabled().map { it.toModel() }

    suspend fun saveWatchedFolder(folder: WatchedFolder) {
        db.watchedFolderDao().upsert(folder.toEntity())
    }

    suspend fun deleteWatchedFolder(id: String) {
        db.watchedFolderDao().delete(id)
    }

    // ── Backup Records ───────────────────────────────────────────

    suspend fun getVerifiedRecords(): List<BackupRecord> =
        db.backupRecordDao().getVerified().map { it.toModel() }

    suspend fun findRecordByUri(uri: String): BackupRecord? =
        db.backupRecordDao().findByLocalUri(uri)?.toModel()

    suspend fun saveRecord(record: BackupRecord) {
        db.backupRecordDao().upsert(record.toEntity())
    }

    suspend fun deleteRecord(id: String) {
        db.backupRecordDao().delete(id)
    }

    suspend fun deleteRecordByUri(uri: String) {
        db.backupRecordDao().deleteByLocalUri(uri)
    }

    suspend fun countVerified(): Int = db.backupRecordDao().countVerified()

    fun observeCountVerified(): Flow<Int> = db.backupRecordDao().observeCountVerified()

    suspend fun findRecord(uri: String, connectionId: String): BackupRecord? =
        db.backupRecordDao().findByUriAndConnection(uri, connectionId)?.toModel()

    // ── Mappers ──────────────────────────────────────────────────

    private fun ConnectionEntity.toModel() = Connection(
        id = id,
        displayName = displayName,
        type = runCatching { ConnectionType.valueOf(type) }.getOrDefault(ConnectionType.OTHER),
        username = username,
        host = host,
        port = port,
        basePath = basePath,
        useTls = useTls,
        isOAuth = isOAuth,
        providerHint = providerHint,
        createdAt = createdAt,
        lastVerifiedAt = lastVerifiedAt,
        isEnabled = isEnabled
    )

    private fun Connection.toEntity() = ConnectionEntity(
        id = id,
        displayName = displayName,
        type = type.name,
        username = username,
        host = host,
        port = port,
        basePath = basePath,
        useTls = useTls,
        isOAuth = isOAuth,
        providerHint = providerHint,
        createdAt = createdAt,
        lastVerifiedAt = lastVerifiedAt,
        isEnabled = isEnabled
    )

    private fun WatchedFolderEntity.toModel() = WatchedFolder(
        id = id,
        localUri = localUri,
        displayPath = displayPath,
        isEnabled = isEnabled,
        connectionId = connectionId,
        remotePath = remotePath,
        fileFilter = runCatching { FileFilter.valueOf(fileFilter) }.getOrDefault(FileFilter.ANY),
        customExtensions = if (customExtensions.isBlank()) emptyList() else customExtensions.split(","),
        lastScanAt = lastScanAt,
        lastBackupAt = lastBackupAt
    )

    private fun WatchedFolder.toEntity() = WatchedFolderEntity(
        id = id,
        localUri = localUri,
        displayPath = displayPath,
        isEnabled = isEnabled,
        connectionId = connectionId,
        remotePath = remotePath,
        fileFilter = fileFilter.name,
        customExtensions = customExtensions.joinToString(","),
        lastScanAt = lastScanAt,
        lastBackupAt = lastBackupAt
    )

    private fun BackupRecordEntity.toModel() = BackupRecord(
        id = id,
        localUri = localUri,
        localPath = localPath,
        remoteConnectionId = remoteConnectionId,
        remotePath = remotePath,
        contentHash = contentHash,
        sizeBytes = sizeBytes,
        backedUpAt = backedUpAt,
        verified = verified
    )

    private fun BackupRecord.toEntity() = BackupRecordEntity(
        id = id,
        localUri = localUri,
        localPath = localPath,
        remoteConnectionId = remoteConnectionId,
        remotePath = remotePath,
        contentHash = contentHash,
        sizeBytes = sizeBytes,
        backedUpAt = backedUpAt,
        verified = verified
    )
}

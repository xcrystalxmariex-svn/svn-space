package space.svn.svnspace.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections ORDER BY displayName")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun getById(id: String): ConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConnectionEntity)

    @Query("DELETE FROM connections WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM connections WHERE isEnabled = 1")
    suspend fun getEnabled(): List<ConnectionEntity>
}

@Dao
interface WatchedFolderDao {
    @Query("SELECT * FROM watched_folders ORDER BY displayPath")
    fun observeAll(): Flow<List<WatchedFolderEntity>>

    @Query("SELECT * FROM watched_folders WHERE isEnabled = 1")
    suspend fun getEnabled(): List<WatchedFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchedFolderEntity)

    @Update
    suspend fun update(entity: WatchedFolderEntity)

    @Query("DELETE FROM watched_folders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM watched_folders WHERE id = :id")
    suspend fun getById(id: String): WatchedFolderEntity?
}

@Dao
interface BackupRecordDao {
    @Query("SELECT * FROM backup_records WHERE verified = 1")
    suspend fun getVerified(): List<BackupRecordEntity>

    @Query("SELECT * FROM backup_records WHERE localUri = :uri LIMIT 1")
    suspend fun findByLocalUri(uri: String): BackupRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BackupRecordEntity)

    @Query("DELETE FROM backup_records WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM backup_records WHERE localUri = :uri")
    suspend fun deleteByLocalUri(uri: String)

    @Query("SELECT COUNT(*) FROM backup_records WHERE verified = 1")
    suspend fun countVerified(): Int

    /**
     * Live count of backed-up files for the dashboard card. Room re-runs it whenever the
     * scan saves a record, so the card counts up during a scan. Each file is counted once
     * (a file can have an old record left on an account its folder used to back up to).
     */
    @Query("SELECT COUNT(DISTINCT localUri) FROM backup_records WHERE verified = 1")
    fun observeCountVerified(): kotlinx.coroutines.flow.Flow<Int>

    /**
     * The record for one file on one account. The scan only asks about the account
     * selected for the file's folder, so a file is uploaded to that one account only.
     */
    @Query("SELECT * FROM backup_records WHERE localUri = :uri AND remoteConnectionId = :connectionId LIMIT 1")
    suspend fun findByUriAndConnection(uri: String, connectionId: String): BackupRecordEntity?
}

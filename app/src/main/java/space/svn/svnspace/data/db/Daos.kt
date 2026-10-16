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
}

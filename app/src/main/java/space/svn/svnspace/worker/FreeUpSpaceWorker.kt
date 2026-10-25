package space.svn.svnspace.worker

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.svn.svnspace.data.repository.SvnRepository

/**
 * Deletes local files that have a verified BackupRecord.
 * Safety: only verified records are touched. Deletion uses DocumentFile / SAF.
 * After successful local delete the record is removed from the database.
 */
class FreeUpSpaceWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = SvnRepository(appContext)
        var freedBytes = 0L
        var freedCount = 0
        var failed = 0

        try {
            val records = repo.getVerifiedRecords()
            Log.i(TAG, "Found ${records.size} verified records to consider")

            for (record in records) {
                try {
                    // Handles both picker (content://) files and Downloads (file://) files.
                    // Without All files access a Downloads file just looks missing; leave it.
                    if (space.svn.svnspace.util.StorageAccess.isFileUri(record.localUri) &&
                        !space.svn.svnspace.util.StorageAccess.hasAllFilesAccess(appContext)
                    ) continue
                    val doc = space.svn.svnspace.util.StorageAccess.singleDocument(appContext, record.localUri)

                    if (doc != null && doc.exists() && doc.isFile) {
                        val size = doc.length()
                        val deleted = doc.delete()
                        if (deleted) {
                            repo.deleteRecord(record.id)
                            freedBytes += size
                            freedCount++
                            Log.i(TAG, "Deleted local: ${record.localPath}")
                        } else {
                            failed++
                            Log.w(TAG, "Could not delete: ${record.localPath}")
                        }
                    } else {
                        // File already gone – clean up the record
                        repo.deleteRecord(record.id)
                        Log.i(TAG, "Record cleaned (file missing): ${record.localPath}")
                    }
                } catch (e: Exception) {
                    failed++
                    Log.e(TAG, "Error processing ${record.localUri}", e)
                }
            }

            val output = workDataOf(
                KEY_FREED_BYTES to freedBytes,
                KEY_FREED_COUNT to freedCount,
                KEY_FAILED to failed
            )
            Log.i(TAG, "Free-up finished: $freedCount files, $freedBytes bytes, $failed failures")
            Result.success(output)
        } catch (e: Exception) {
            Log.e(TAG, "FreeUpSpaceWorker failed", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "FreeUpSpaceWorker"
        const val KEY_FREED_BYTES = "freed_bytes"
        const val KEY_FREED_COUNT = "freed_count"
        const val KEY_FAILED = "failed"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<FreeUpSpaceWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

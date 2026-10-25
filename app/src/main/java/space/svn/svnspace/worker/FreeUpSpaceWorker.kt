package space.svn.svnspace.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.svn.svnspace.data.repository.SvnRepository
import java.util.UUID

/**
 * Deletes local files that have a verified BackupRecord.
 * Safety: only verified records are touched. Deletion uses DocumentFile / SAF.
 * After successful local delete the file's records are removed from the database.
 * The list comes from [FreeUpSpacePlan], the same one the confirmation dialog totals.
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
            val plan = FreeUpSpacePlan.build(appContext, repo)
            Log.i(TAG, "Plan: ${plan.fileCount} files, ${plan.totalBytes} bytes, ${plan.staleRecords.size} stale records")

            for (c in plan.candidates) {
                try {
                    if (c.doc.delete()) {
                        c.records.forEach { repo.deleteRecord(it.id) }
                        freedBytes += c.sizeBytes
                        freedCount++
                        Log.i(TAG, "Deleted local: ${c.records.first().localPath}")
                    } else {
                        failed++
                        Log.w(TAG, "Could not delete: ${c.records.first().localPath}")
                    }
                } catch (e: Exception) {
                    failed++
                    Log.e(TAG, "Error deleting ${c.records.first().localUri}", e)
                }
            }
            // File already gone – clean up its records.
            plan.staleRecords.forEach { runCatching { repo.deleteRecord(it.id) } }

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

        fun enqueue(context: Context): UUID {
            val request = OneTimeWorkRequestBuilder<FreeUpSpaceWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }
    }
}

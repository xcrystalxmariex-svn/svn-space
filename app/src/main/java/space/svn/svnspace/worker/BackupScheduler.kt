package space.svn.svnspace.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object BackupScheduler {

    private const val UNIQUE_WORK = "svn_space_periodic_scan"
    const val SCAN_NOW_WORK = "svn_space_scan_now"

    private fun periodicConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun ensurePeriodicWork(context: Context) {
        // Default interval: 1 hour. User can change this in settings later.
        val request = PeriodicWorkRequestBuilder<ScanAndBackupWorker>(
            1, TimeUnit.HOURS
        )
            .setConstraints(periodicConstraints())
            .addTag("scan")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun updateInterval(context: Context, hours: Long) {
        val request = PeriodicWorkRequestBuilder<ScanAndBackupWorker>(
            hours, TimeUnit.HOURS
        )
            .setConstraints(periodicConstraints())
            .addTag("scan")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * User-started scan. Expedited so it starts right away instead of waiting for the
     * system's next maintenance window, with no constraints (the worker reports
     * "no internet" itself rather than sitting queued). Unique, so repeated taps
     * don't stack up scans; the dashboard watches this name for progress.
     */
    fun scanNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ScanAndBackupWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(ScanAndBackupWorker.KEY_MANUAL to true))
            .addTag("scan")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SCAN_NOW_WORK,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}

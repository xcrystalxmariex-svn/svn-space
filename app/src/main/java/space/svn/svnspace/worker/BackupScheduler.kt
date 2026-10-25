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
    const val PREF_INTERVAL = "pref_scan_interval_min"

    val INTERVAL_MINUTES = longArrayOf(15, 30, 60, 120, 360, 720, 1440)
    val INTERVAL_LABELS = arrayOf("Every 15 minutes", "Every 30 minutes", "Every hour", "Every 2 hours", "Every 6 hours", "Every 12 hours", "Every 24 hours")

    fun intervalMinutes(context: Context): Long =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_INTERVAL, "60")?.toLongOrNull() ?: 60L

    fun intervalLabel(minutes: Long): String =
        INTERVAL_LABELS.getOrNull(INTERVAL_MINUTES.indexOf(minutes)) ?: "Every $minutes minutes"

    private fun periodicConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun ensurePeriodicWork(context: Context) {
        // Default interval: 1 hour. User can change this in settings later.
        val request = PeriodicWorkRequestBuilder<ScanAndBackupWorker>(
            intervalMinutes(context).coerceAtLeast(15), TimeUnit.MINUTES
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

    /** Interval in minutes (WorkManager's minimum is 15). */
    fun updateInterval(context: Context, minutes: Long) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_INTERVAL, minutes.toString()).apply()
        val request = PeriodicWorkRequestBuilder<ScanAndBackupWorker>(
            minutes.coerceAtLeast(15), TimeUnit.MINUTES
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

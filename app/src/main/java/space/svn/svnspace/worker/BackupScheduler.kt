package space.svn.svnspace.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BackupScheduler {

    private const val UNIQUE_WORK = "svn_space_periodic_scan"

    fun ensurePeriodicWork(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        // Default interval: 1 hour. User can change this in settings later.
        val request = PeriodicWorkRequestBuilder<ScanAndBackupWorker>(
            1, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .addTag("scan")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun updateInterval(context: Context, hours: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ScanAndBackupWorker>(
            hours, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

package space.svn.svnspace.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-schedules the periodic scan after device boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            BackupScheduler.ensurePeriodicWork(context)
        }
    }
}

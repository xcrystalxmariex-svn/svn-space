package space.svn.svnspace

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import space.svn.svnspace.util.ThemeManager
import space.svn.svnspace.worker.BackupScheduler

class SvnSpaceApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannels()
        ThemeManager.applySavedTheme(this)
        BackupScheduler.ensurePeriodicWork(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val backupChannel = NotificationChannel(
                CHANNEL_BACKUP,
                "Backup & Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background scanning and upload progress"
            }

            val statusChannel = NotificationChannel(
                CHANNEL_STATUS,
                "Status",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Important status and errors"
            }

            manager.createNotificationChannel(backupChannel)
            manager.createNotificationChannel(statusChannel)
        }
    }

    companion object {
        const val CHANNEL_BACKUP = "svn_space_backup"
        const val CHANNEL_STATUS = "svn_space_status"

        lateinit var instance: SvnSpaceApp
            private set
    }
}

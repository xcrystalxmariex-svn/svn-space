package space.svn.svnspace.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * One place that turns a watched folder's stored URI into something we can walk.
 *
 * Two kinds of watched folder exist:
 *  - content://  tree URIs from the system folder picker (Storage Access Framework).
 *  - file://     plain paths, used for folders the picker refuses to hand out.
 *    Since Android 11 the picker blocks the Downloads root (and the storage root),
 *    so Downloads is added as a file:// path and read with "All files access"
 *    (MANAGE_EXTERNAL_STORAGE) on 11+, or READ/WRITE_EXTERNAL_STORAGE on 8–10.
 */
object StorageAccess {

    fun isFileUri(uriString: String): Boolean =
        uriString.startsWith("file:") || uriString.startsWith("/")

    fun fileOf(uriString: String): File =
        if (uriString.startsWith("/")) File(uriString) else File(Uri.parse(uriString).path ?: uriString)

    /** Folders offered under Add Folder: label to Environment.DIRECTORY_* name. */
    val COMMON_FOLDERS: List<Pair<String, String>> = listOf(
        "Downloads" to Environment.DIRECTORY_DOWNLOADS,
        "DCIM (camera)" to Environment.DIRECTORY_DCIM,
        "Pictures" to Environment.DIRECTORY_PICTURES,
        "Movies" to Environment.DIRECTORY_MOVIES,
        "Music" to Environment.DIRECTORY_MUSIC,
        "Documents" to Environment.DIRECTORY_DOCUMENTS
    )

    fun publicDirUri(dirType: String): String =
        Uri.fromFile(Environment.getExternalStoragePublicDirectory(dirType)).toString()

    fun downloadsDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    fun downloadsUri(): String = Uri.fromFile(downloadsDir()).toString()

    /** True when the app may read (and delete in) plain storage paths like Downloads. */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Root of a watched folder, or null when it can't be opened right now. */
    fun rootOf(context: Context, localUri: String): DocumentFile? {
        return if (isFileUri(localUri)) {
            val dir = fileOf(localUri)
            if (dir.isDirectory) DocumentFile.fromFile(dir) else null
        } else {
            try {
                DocumentFile.fromTreeUri(context, Uri.parse(localUri))
            } catch (e: Exception) {
                null
            }
        }
    }

    /** A single file previously recorded by a backup (content:// document or file://). */
    fun singleDocument(context: Context, uriString: String): DocumentFile? {
        return if (isFileUri(uriString)) {
            DocumentFile.fromFile(fileOf(uriString))
        } else {
            DocumentFile.fromSingleUri(context, Uri.parse(uriString))
        }
    }

    /**
     * Why a watched folder can't be read right now, in plain words, or null when it's fine.
     */
    fun problemWith(context: Context, localUri: String): String? {
        if (isFileUri(localUri)) {
            if (!hasAllFilesAccess(context)) return "Needs All files access – tap to fix"
            val dir = fileOf(localUri)
            if (!dir.isDirectory) return "Folder not found"
            return null
        }
        val uri = Uri.parse(localUri)
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        return if (granted) null else "Access lost – remove and add this folder again"
    }

    // ── Per-folder extras (kept outside the database, no migration needed) ──

    private const val FOLDER_PREFS = "folder_options"

    /** Minutes between scans for one folder; 0 = every scheduled scan. */
    fun folderIntervalMinutes(context: Context, folderId: String): Long =
        context.getSharedPreferences(FOLDER_PREFS, Context.MODE_PRIVATE).getLong("${folderId}_interval", 0L)

    fun pauseOnLowBattery(context: Context, folderId: String): Boolean =
        context.getSharedPreferences(FOLDER_PREFS, Context.MODE_PRIVATE).getBoolean("${folderId}_low_battery", true)

    fun saveFolderOptions(context: Context, folderId: String, intervalMinutes: Long, pauseLowBattery: Boolean) {
        context.getSharedPreferences(FOLDER_PREFS, Context.MODE_PRIVATE).edit()
            .putLong("${folderId}_interval", intervalMinutes)
            .putBoolean("${folderId}_low_battery", pauseLowBattery)
            .apply()
    }

    fun isBatteryLow(context: Context): Boolean {
        val bm = context.getSystemService(android.os.BatteryManager::class.java) ?: return false
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level in 0..15 && !bm.isCharging
    }
}

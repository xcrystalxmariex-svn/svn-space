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

    // ── Whole internal storage ──

    /** The shared internal storage root, normally /storage/emulated/0. */
    fun internalRoot(): File = Environment.getExternalStorageDirectory()

    fun internalRootUri(): String = Uri.fromFile(internalRoot()).toString()

    fun isInternalRoot(localUri: String): Boolean =
        isFileUri(localUri) && fileOf(localUri).absolutePath.trimEnd('/') == internalRoot().absolutePath.trimEnd('/')

    /** Paths under the storage root that Android never lets apps read. Always skipped. */
    val ALWAYS_SKIPPED = listOf("Android/data", "Android/obb")

    /**
     * Default excludes for folders read with All files access. Each line is a name pattern
     * (* wildcard) matched against every folder and file name, or a path relative to the
     * watched folder when it contains a slash. Users can edit them per folder.
     */
    val DEFAULT_EXCLUDES = listOf(
        ".thumbnails", ".cache", "cache", "Cache", ".Trash*", ".trash*", ".trashed-*", ".pending-*",
        "LOST.DIR", ".nomedia", "*.tmp", "*.temp", "*.part", "*.crdownload", "~*", ".~*"
    )

    /** Top-level folders of internal storage for "Add all folders" (no hidden ones, no Android/). */
    fun topLevelFolders(): List<File> =
        internalRoot().listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** Opens the system "All files access" screen for this app (Android 11+). */
    fun allFilesAccessIntent(context: Context): android.content.Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    /** Fallback when the per-app screen isn't available on this phone. */
    fun allFilesAccessListIntent(): android.content.Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        } else null

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

    fun excludes(context: Context, folderId: String): List<String> {
        val raw = context.getSharedPreferences(FOLDER_PREFS, Context.MODE_PRIVATE)
            .getString("${folderId}_excludes", null) ?: return emptyList()
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveExcludes(context: Context, folderId: String, patterns: List<String>) {
        context.getSharedPreferences(FOLDER_PREFS, Context.MODE_PRIVATE).edit()
            .putString("${folderId}_excludes", patterns.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
            .apply()
    }

    /** Last time a scan of this folder finished completely (for incremental scans), 0 if never. */
    fun lastCompleteScan(context: Context, folderId: String): Long =
        context.getSharedPreferences(FOLDER_PREFS, Context.MODE_PRIVATE).getLong("${folderId}_complete", 0L)

    fun lastFullScan(context: Context, folderId: String): Long =
        context.getSharedPreferences(FOLDER_PREFS, Context.MODE_PRIVATE).getLong("${folderId}_full", 0L)

    fun markScanComplete(context: Context, folderId: String, startedAt: Long, wasFull: Boolean) {
        context.getSharedPreferences(FOLDER_PREFS, Context.MODE_PRIVATE).edit()
            .putLong("${folderId}_complete", startedAt)
            .apply { if (wasFull) putLong("${folderId}_full", startedAt) }
            .apply()
    }

    /** Forget incremental state, so the next scan looks at every file again. */
    fun resetScanState(context: Context, folderId: String) {
        context.getSharedPreferences(FOLDER_PREFS, Context.MODE_PRIVATE).edit()
            .remove("${folderId}_complete").remove("${folderId}_full").apply()
    }

    /** All per-folder extras, for the settings backup. */
    fun allFolderOptions(context: Context): Map<String, *> =
        context.getSharedPreferences(FOLDER_PREFS, Context.MODE_PRIVATE).all

    fun folderPrefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(FOLDER_PREFS, Context.MODE_PRIVATE)

    /** Simple matcher for exclude patterns: * wildcard, case-insensitive. */
    class ExcludeMatcher(patterns: List<String>) {
        private val namePatterns = ArrayList<Regex>()
        private val pathPatterns = ArrayList<Regex>()

        init {
            for (p in patterns) {
                val clean = p.trim().trim('/')
                if (clean.isEmpty()) continue
                val body = clean.split('*').joinToString(".*") { Regex.escape(it) }
                if (clean.contains('/')) {
                    // A path also covers everything inside it.
                    pathPatterns.add(Regex("$body(/.*)?", RegexOption.IGNORE_CASE))
                } else {
                    namePatterns.add(Regex(body, RegexOption.IGNORE_CASE))
                }
            }
        }

        /** [relativePath] is relative to the watched folder, like "DCIM/.thumbnails". */
        fun excluded(name: String, relativePath: String): Boolean =
            namePatterns.any { it.matches(name) } ||
                pathPatterns.any { it.matches(relativePath) }
    }

    fun isBatteryLow(context: Context): Boolean {
        val bm = context.getSystemService(android.os.BatteryManager::class.java) ?: return false
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level in 0..15 && !bm.isCharging
    }
}

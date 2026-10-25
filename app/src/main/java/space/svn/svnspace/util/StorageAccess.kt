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
}

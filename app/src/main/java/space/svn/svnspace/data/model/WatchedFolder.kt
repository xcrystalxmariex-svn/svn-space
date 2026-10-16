package space.svn.svnspace.data.model

import java.util.UUID

/**
 * A local folder the user has toggled on for watching + backup.
 */
data class WatchedFolder(
    val id: String = UUID.randomUUID().toString(),
    val localUri: String,                 // content:// or file:// URI
    val displayPath: String,              // human readable
    val isEnabled: Boolean = true,
    val connectionId: String? = null,     // which remote this folder backs up to
    val remotePath: String? = null,       // path inside the remote tree
    val fileFilter: FileFilter = FileFilter.ANY,
    val customExtensions: List<String> = emptyList(), // used when filter == CUSTOM
    val lastScanAt: Long? = null,
    val lastBackupAt: Long? = null
)

enum class FileFilter {
    ANY,
    IMAGES,
    VIDEOS,
    DOCUMENTS,
    AUDIO,
    CUSTOM
}

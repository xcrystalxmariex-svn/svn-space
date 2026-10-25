package space.svn.svnspace.ui.browser

import space.svn.svnspace.data.remote.GoogleDriveClient
import space.svn.svnspace.data.remote.WebDavClient
import java.io.File
import java.io.IOException

/** A place in a cloud account the browser can show. */
data class CloudLocation(
    /** Drive: folder ID ("root" for My Drive). WebDAV: path relative to the server base. */
    val key: String,
    val name: String,
    val kind: Kind = Kind.FOLDER,
    /** Drive shared drive this folder lives in, if any. */
    val driveId: String? = null,
    val writable: Boolean = true
) {
    enum class Kind { ACCOUNT_ROOT, FOLDER, SHARED_WITH_ME }
}

/** One file or folder in a listing, the same shape for every provider. */
data class CloudItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    /** -1 when unknown (folders, Google Docs). */
    val size: Long,
    val modifiedAt: Long,
    val mimeType: String?,
    val webLink: String? = null,
    val canRename: Boolean = true,
    val canDelete: Boolean = true,
    val canMove: Boolean = true,
    /** Google Docs / Sheets / Slides: no bytes to download, only export or open in the browser. */
    val isGoogleDoc: Boolean = false,
    /** Where opening a folder item goes (shortcuts and shared drives point elsewhere). */
    val target: CloudLocation? = null
) {
    val exportFormat: Pair<String, String>? get() =
        if (isGoogleDoc) GoogleDriveClient.exportFormat(mimeType ?: "") else null
}

data class CloudPage(val items: List<CloudItem>, val nextPageToken: String?)

/** What the cloud browser needs from a provider. All calls block; run them off the main thread. */
interface CloudSource {
    val root: CloudLocation
    /** Delete moves to the provider's trash (Drive) rather than removing for good. */
    val deleteIsTrash: Boolean
    fun list(location: CloudLocation, pageToken: String?): CloudPage
    fun download(item: CloudItem, out: File, onProgress: (Long, Long) -> Unit)
    fun upload(location: CloudLocation, file: File, name: String, mimeType: String)
    fun createFolder(location: CloudLocation, name: String)
    fun rename(item: CloudItem, location: CloudLocation, newName: String)
    fun move(item: CloudItem, from: CloudLocation, to: CloudLocation)
    fun delete(item: CloudItem, location: CloudLocation)
}

class DriveSource(private val drive: GoogleDriveClient) : CloudSource {
    override val root = CloudLocation("", "Google Drive", CloudLocation.Kind.ACCOUNT_ROOT, writable = false)
    override val deleteIsTrash = true

    /** False for an old drive.file login that only sees files svn-space made; null if offline. */
    fun grantedFullDrive(): Boolean? = drive.grantedFullDrive()

    private fun toItem(d: GoogleDriveClient.DriveItem): CloudItem {
        val folderTarget = if (d.isFolder) {
            CloudLocation(d.targetId ?: d.id, d.name, CloudLocation.Kind.FOLDER, d.driveId, d.canAddChildren)
        } else null
        return CloudItem(
            id = d.id,
            name = d.name,
            isFolder = d.isFolder,
            size = if (d.isFolder) -1 else d.size,
            modifiedAt = d.modifiedAt,
            mimeType = d.targetMimeType ?: d.mimeType,
            webLink = d.webViewLink,
            canRename = d.canRename,
            canDelete = d.canTrash,
            canMove = d.canMove,
            isGoogleDoc = (d.targetMimeType ?: d.mimeType).startsWith("application/vnd.google-apps.") && !d.isFolder,
            target = folderTarget
        )
    }

    override fun list(location: CloudLocation, pageToken: String?): CloudPage = when (location.kind) {
        CloudLocation.Kind.ACCOUNT_ROOT -> {
            // Top level: My Drive, Shared with me, then each shared drive.
            val items = mutableListOf(
                CloudItem("root", "My Drive", true, -1, 0, null,
                    canRename = false, canDelete = false, canMove = false,
                    target = CloudLocation("root", "My Drive")),
                CloudItem("shared", "Shared with me", true, -1, 0, null,
                    canRename = false, canDelete = false, canMove = false,
                    target = CloudLocation("shared", "Shared with me", CloudLocation.Kind.SHARED_WITH_ME, writable = false))
            )
            try {
                drive.listSharedDrives().forEach { sd ->
                    items.add(
                        CloudItem(sd.id, sd.name, true, -1, 0, "shared-drive",
                            canRename = false, canDelete = false, canMove = false,
                            target = CloudLocation(sd.id, sd.name, CloudLocation.Kind.FOLDER, sd.id))
                    )
                }
            } catch (e: GoogleDriveClient.ReauthRequiredException) {
                throw e
            } catch (_: Exception) {
                // Shared drives are a bonus; personal accounts often have none.
            }
            CloudPage(items, null)
        }
        CloudLocation.Kind.SHARED_WITH_ME -> drive.listSharedWithMe(pageToken).let { p ->
            CloudPage(p.items.map(::toItem), p.nextPageToken)
        }
        CloudLocation.Kind.FOLDER -> drive.listChildren(location.key, location.driveId, pageToken).let { p ->
            CloudPage(p.items.map(::toItem), p.nextPageToken)
        }
    }

    override fun download(item: CloudItem, out: File, onProgress: (Long, Long) -> Unit) {
        if (item.isGoogleDoc) {
            val (mime, _) = item.exportFormat ?: throw IOException("This Google file type can only be opened in the browser")
            drive.export(item.id, mime, out, onProgress)
        } else {
            drive.download(item.id, out, onProgress)
        }
    }

    override fun upload(location: CloudLocation, file: File, name: String, mimeType: String) {
        drive.uploadInto(location.key, file, name, mimeType)
    }

    override fun createFolder(location: CloudLocation, name: String) {
        drive.createFolderIn(location.key, name)
    }

    override fun rename(item: CloudItem, location: CloudLocation, newName: String) {
        drive.rename(item.id, newName)
    }

    override fun move(item: CloudItem, from: CloudLocation, to: CloudLocation) {
        drive.move(item.id, to.key)
    }

    override fun delete(item: CloudItem, location: CloudLocation) {
        drive.trash(item.id)
    }
}

class WebDavSource(private val dav: WebDavClient, accountName: String) : CloudSource {
    override val root = CloudLocation("/", accountName, CloudLocation.Kind.FOLDER)
    override val deleteIsTrash = false

    private fun join(dir: String, name: String) = (dir.trimEnd('/') + "/" + name).let { if (it.startsWith("/")) it else "/$it" }

    override fun list(location: CloudLocation, pageToken: String?): CloudPage {
        val items = dav.list(location.key).map { e ->
            CloudItem(
                id = e.path,
                name = e.name,
                isFolder = e.isDirectory,
                size = if (e.isDirectory) -1 else e.size,
                modifiedAt = e.modifiedAt,
                mimeType = e.contentType,
                webLink = dav.urlFor(e.path),
                target = if (e.isDirectory) CloudLocation(e.path, e.name) else null
            )
        }.sortedWith(compareBy<CloudItem>({ !it.isFolder }, { it.name.lowercase() }))
        return CloudPage(items, null)
    }

    override fun download(item: CloudItem, out: File, onProgress: (Long, Long) -> Unit) =
        dav.download(item.id, out, onProgress)

    override fun upload(location: CloudLocation, file: File, name: String, mimeType: String) =
        dav.upload(file, join(location.key, name), mimeType)

    override fun createFolder(location: CloudLocation, name: String) = dav.mkcol(join(location.key, name))

    override fun rename(item: CloudItem, location: CloudLocation, newName: String) =
        dav.move(item.id, join(item.id.substringBeforeLast('/', "/"), newName), item.isFolder)

    override fun move(item: CloudItem, from: CloudLocation, to: CloudLocation) {
        if (item.isFolder && (to.key == item.id || to.key.startsWith(item.id.trimEnd('/') + "/"))) {
            throw IOException("Can't move a folder into itself")
        }
        dav.move(item.id, join(to.key, item.name), item.isFolder)
    }

    override fun delete(item: CloudItem, location: CloudLocation) = dav.delete(item.id, item.isFolder)
}

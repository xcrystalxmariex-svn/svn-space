package space.svn.svnspace.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.svn.svnspace.data.model.BackupRecord
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.model.FileFilter
import space.svn.svnspace.data.remote.WebDavClient
import space.svn.svnspace.data.repository.SvnRepository
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Scans enabled watched folders, applies filters, uploads via the assigned connection,
 * and records verified BackupRecords on success.
 * Currently implements the WebDAV path fully. OAuth providers are extension points.
 */
class ScanAndBackupWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = SvnRepository(appContext)
        try {
            val folders = repo.getEnabledWatchedFolders()
            Log.i(TAG, "Scanning ${folders.size} watched folders")

            for (folder in folders) {
                if (folder.connectionId == null) {
                    Log.w(TAG, "Folder ${folder.displayPath} has no connection assigned – skip")
                    continue
                }
                val conn = repo.getConnection(folder.connectionId) ?: continue
                val secret = repo.getSecret(conn.id) ?: continue

                when (conn.type) {
                    ConnectionType.WEBDAV, ConnectionType.CUSTOM_HTTP -> {
                        processWebDavFolder(repo, folder, conn, secret)
                    }
                    else -> {
                        Log.i(TAG, "Provider ${conn.type} not yet implemented in worker – skip")
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "ScanAndBackupWorker failed", e)
            Result.retry()
        }
    }

    private suspend fun processWebDavFolder(
        repo: SvnRepository,
        folder: space.svn.svnspace.data.model.WatchedFolder,
        conn: space.svn.svnspace.data.model.Connection,
        password: String
    ) {
        val base = (conn.host ?: return).trimEnd('/')
        val client = WebDavClient(
            baseUrl = base,
            username = conn.username ?: "",
            password = password,
            useTls = conn.useTls
        )

        val treeUri = Uri.parse(folder.localUri)
        val root = DocumentFile.fromTreeUri(appContext, treeUri) ?: return

        val remoteBase = (folder.remotePath ?: conn.basePath ?: "/").trimEnd('/')

        try {
            client.mkcol(remoteBase)
        } catch (_: Exception) { /* may already exist */ }

        walkAndUpload(repo, root, client, remoteBase, folder, conn.id)
    }

    private suspend fun walkAndUpload(
        repo: SvnRepository,
        dir: DocumentFile,
        client: WebDavClient,
        remoteDir: String,
        folder: space.svn.svnspace.data.model.WatchedFolder,
        connectionId: String
    ) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                val subRemote = "$remoteDir/${child.name}"
                try { client.mkcol(subRemote) } catch (_: Exception) {}
                walkAndUpload(repo, child, client, subRemote, folder, connectionId)
            } else if (child.isFile && matchesFilter(child, folder)) {
                val existing = repo.findRecordByUri(child.uri.toString())
                if (existing != null && existing.verified) continue

                val cacheFile = File(appContext.cacheDir, "upload_${child.name}")
                try {
                    appContext.contentResolver.openInputStream(child.uri)?.use { input ->
                        FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                    } ?: continue

                    val remotePath = "$remoteDir/${child.name}"
                    client.upload(cacheFile, remotePath)

                    val hash = sha256(cacheFile)
                    val record = BackupRecord(
                        id = UUID.randomUUID().toString(),
                        localUri = child.uri.toString(),
                        localPath = child.name ?: child.uri.toString(),
                        remoteConnectionId = connectionId,
                        remotePath = remotePath,
                        contentHash = hash,
                        sizeBytes = child.length(),
                        verified = true
                    )
                    repo.saveRecord(record)
                    Log.i(TAG, "Backed up: ${child.name} → $remotePath")
                } catch (e: Exception) {
                    Log.e(TAG, "Upload failed for ${child.name}", e)
                } finally {
                    cacheFile.delete()
                }
            }
        }
    }

    private fun matchesFilter(file: DocumentFile, folder: space.svn.svnspace.data.model.WatchedFolder): Boolean {
        val name = file.name?.lowercase() ?: return false
        return when (folder.fileFilter) {
            FileFilter.ANY -> true
            FileFilter.IMAGES -> name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
                    name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".heic")
            FileFilter.VIDEOS -> name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".mov") ||
                    name.endsWith(".avi") || name.endsWith(".webm")
            FileFilter.DOCUMENTS -> name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx") ||
                    name.endsWith(".txt") || name.endsWith(".odt") || name.endsWith(".xls") || name.endsWith(".xlsx")
            FileFilter.AUDIO -> name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".wav") ||
                    name.endsWith(".aac") || name.endsWith(".ogg")
            FileFilter.CUSTOM -> folder.customExtensions.any { name.endsWith(".${it.lowercase().removePrefix(".")}") }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var read: Int
            while (input.read(buf).also { read = it } > 0) {
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ScanAndBackupWorker"
    }
}

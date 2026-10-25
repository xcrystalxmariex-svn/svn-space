package space.svn.svnspace.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.svn.svnspace.SvnSpaceApp
import space.svn.svnspace.data.model.BackupRecord
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.model.FileFilter
import space.svn.svnspace.data.model.WatchedFolder
import space.svn.svnspace.data.remote.GoogleDriveClient
import space.svn.svnspace.data.remote.WebDavClient
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.ui.connections.OAuthHelper
import space.svn.svnspace.util.StorageAccess
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Scans enabled watched folders, applies filters, uploads via the assigned connection,
 * and records verified BackupRecords on success.
 * Implements WebDAV and Google Drive. OneDrive / Dropbox are still extension points.
 *
 * Every run ends with a summary (output data + saved for the dashboard) so a scan that
 * had nothing to do, or couldn't reach a folder, says so instead of looking stuck.
 * One folder failing no longer sends the whole job into an endless retry loop.
 */
class ScanAndBackupWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private class Stats {
        var foldersScanned = 0
        var uploaded = 0
        var alreadyBackedUp = 0
        var failedFiles = 0
        var noDestination = 0
        val problems = mutableListOf<String>()
    }

    private val stats = Stats()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(appContext, SvnSpaceApp.CHANNEL_BACKUP)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("svn-space")
            .setContentText("Scanning watched folders…")
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (manual) {
            // A user-started scan may upload a lot; run it as a foreground job so Android
            // doesn't stop it after ~10 minutes. Not allowed from the background on 12+,
            // so failures here are fine – the scan still runs.
            try {
                setForeground(getForegroundInfo())
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't run scan in the foreground: ${e.message}")
            }
        }

        val repo = SvnRepository(appContext)
        try {
            val folders = repo.getEnabledWatchedFolders()
            val connections = repo.getEnabledConnections()
            Log.i(TAG, "Scanning ${folders.size} watched folders")

            if (folders.isEmpty()) {
                stats.problems.add("No watched folders turned on")
                return@withContext finish(Result.success())
            }
            if (!isOnline()) {
                stats.problems.add("No internet connection")
                return@withContext finish(Result.success())
            }

            for (original in folders) {
                var folder = original
                setProgress(workDataOf(KEY_CURRENT to folder.displayPath, KEY_UPLOADED to stats.uploaded))

                // Folders added before per-folder settings worked have no destination.
                // With exactly one connection there's only one place they can go.
                if (folder.connectionId == null && connections.size == 1) {
                    folder = folder.copy(connectionId = connections[0].id)
                    repo.saveWatchedFolder(folder)
                }
                val connId = folder.connectionId
                if (connId == null) {
                    stats.noDestination++
                    Log.w(TAG, "Folder ${folder.displayPath} has no connection assigned – skip")
                    continue
                }
                val conn = repo.getConnection(connId)
                if (conn == null || !conn.isEnabled) {
                    stats.noDestination++
                    continue
                }
                val secret = repo.getSecret(conn.id)
                if (secret == null) {
                    stats.problems.add("${conn.displayName}: sign in again")
                    continue
                }
                val problem = StorageAccess.problemWith(appContext, folder.localUri)
                val root = if (problem == null) StorageAccess.rootOf(appContext, folder.localUri) else null
                if (root == null) {
                    stats.problems.add("${folder.displayPath}: ${problem ?: "can't open folder"}")
                    continue
                }

                val before = stats.uploaded
                try {
                    when (conn.type) {
                        ConnectionType.WEBDAV, ConnectionType.CUSTOM_HTTP ->
                            processWebDavFolder(repo, root, folder, conn, secret)
                        ConnectionType.OAUTH_GOOGLE ->
                            processDriveFolder(repo, root, folder, conn, secret)
                        else -> {
                            stats.problems.add("${conn.displayName}: uploads not supported yet")
                            continue
                        }
                    }
                    stats.foldersScanned++
                } catch (e: GoogleDriveClient.ReauthRequiredException) {
                    Log.w(TAG, "Drive login for ${conn.displayName} needs reconnecting: ${e.message}")
                    stats.problems.add("${conn.displayName}: Google sign-in expired, connect it again")
                } catch (e: Exception) {
                    Log.e(TAG, "Folder ${folder.displayPath} failed", e)
                    stats.problems.add("${folder.displayPath}: ${e.message ?: e.javaClass.simpleName}")
                }
                val now = System.currentTimeMillis()
                repo.saveWatchedFolder(
                    folder.copy(
                        lastScanAt = now,
                        lastBackupAt = if (stats.uploaded > before) now else folder.lastBackupAt
                    )
                )
            }
            finish(Result.success())
        } catch (e: Exception) {
            Log.e(TAG, "ScanAndBackupWorker failed", e)
            stats.problems.add(e.message ?: e.javaClass.simpleName)
            finish(Result.failure())
        }
    }

    /** Saves the summary for the dashboard and attaches it to the result. */
    private fun finish(result: Result): Result {
        val summary = buildSummary()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_SUMMARY, summary)
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .apply()
        Log.i(TAG, "Scan finished: $summary")
        val out: Data = workDataOf(
            KEY_SUMMARY to summary,
            KEY_UPLOADED to stats.uploaded,
            KEY_FAILED to stats.failedFiles
        )
        return when (result) {
            is Result.Failure -> Result.failure(out)
            else -> Result.success(out)
        }
    }

    private fun buildSummary(): String {
        val parts = mutableListOf<String>()
        if (stats.foldersScanned > 0) {
            parts.add(
                "${stats.uploaded} uploaded, ${stats.alreadyBackedUp} already backed up" +
                    (if (stats.failedFiles > 0) ", ${stats.failedFiles} failed" else "")
            )
        }
        if (stats.noDestination > 0) {
            parts.add(
                "${stats.noDestination} folder" + (if (stats.noDestination == 1) "" else "s") +
                    " with no backup destination – set one in Settings → Per-folder advanced settings"
            )
        }
        parts.addAll(stats.problems.take(3))
        return parts.joinToString("\n").ifBlank { "Nothing to back up" }
    }

    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Upload target used by the folder walk, so WebDAV and Drive share one code path. */
    private interface RemoteTarget {
        fun mkdir(path: String)
        fun upload(file: File, path: String)
    }

    private suspend fun processDriveFolder(
        repo: SvnRepository,
        root: DocumentFile,
        folder: WatchedFolder,
        conn: Connection,
        refreshToken: String
    ) {
        val drive = GoogleDriveClient(OAuthHelper.GOOGLE_CLIENT_ID, refreshToken)
        val folderName = root.name ?: folder.displayPath.ifBlank { "Backup" }
        val remoteBase = (folder.remotePath?.takeIf { it.isNotBlank() && it != "/" }
            ?: "${conn.basePath ?: GoogleDriveClient.DEFAULT_ROOT}/$folderName").trimEnd('/')
        drive.ensureFolder(remoteBase)
        val target = object : RemoteTarget {
            override fun mkdir(path: String) { drive.ensureFolder(path) }
            override fun upload(file: File, path: String) { drive.upload(file, path) }
        }
        walkAndUpload(repo, root, target, remoteBase, folder, conn.id)
    }

    private suspend fun processWebDavFolder(
        repo: SvnRepository,
        root: DocumentFile,
        folder: WatchedFolder,
        conn: Connection,
        password: String
    ) {
        val base = (conn.host ?: throw IllegalStateException("server address missing")).trimEnd('/')
        val client = WebDavClient(
            baseUrl = base,
            username = conn.username ?: "",
            password = password,
            useTls = conn.useTls
        )

        val remoteBase = (folder.remotePath ?: conn.basePath ?: "/").trimEnd('/')

        try {
            client.mkcol(remoteBase)
        } catch (_: Exception) { /* may already exist */ }

        val target = object : RemoteTarget {
            override fun mkdir(path: String) { client.mkcol(path) }
            override fun upload(file: File, path: String) { client.upload(file, path) }
        }
        walkAndUpload(repo, root, target, remoteBase, folder, conn.id)
    }

    private suspend fun walkAndUpload(
        repo: SvnRepository,
        dir: DocumentFile,
        client: RemoteTarget,
        remoteDir: String,
        folder: WatchedFolder,
        connectionId: String
    ) {
        if (isStopped) return
        val children = dir.listFiles()
        for (child in children) {
            if (isStopped) return
            val childName = child.name ?: continue
            // Half-finished downloads and trashed items aren't real files yet.
            if (childName.startsWith(".pending-") || childName.startsWith(".trashed-")) continue
            if (child.isDirectory) {
                val subRemote = "$remoteDir/$childName"
                try { client.mkdir(subRemote) } catch (_: Exception) {}
                walkAndUpload(repo, child, client, subRemote, folder, connectionId)
            } else if (child.isFile && matchesFilter(child, folder)) {
                val existing = repo.findRecordByUri(child.uri.toString())
                if (existing != null && existing.verified) {
                    stats.alreadyBackedUp++
                    continue
                }

                val cacheFile = File(appContext.cacheDir, "upload_${UUID.randomUUID()}")
                try {
                    val copied = appContext.contentResolver.openInputStream(child.uri)?.use { input ->
                        FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                        true
                    } ?: false
                    if (!copied) {
                        stats.failedFiles++
                        continue
                    }

                    val remotePath = "$remoteDir/$childName"
                    client.upload(cacheFile, remotePath)

                    val hash = sha256(cacheFile)
                    val record = BackupRecord(
                        id = UUID.randomUUID().toString(),
                        localUri = child.uri.toString(),
                        localPath = childName,
                        remoteConnectionId = connectionId,
                        remotePath = remotePath,
                        contentHash = hash,
                        sizeBytes = child.length(),
                        verified = true
                    )
                    repo.saveRecord(record)
                    stats.uploaded++
                    setProgress(workDataOf(KEY_CURRENT to folder.displayPath, KEY_UPLOADED to stats.uploaded))
                    Log.i(TAG, "Backed up: $childName → $remotePath")
                } catch (e: GoogleDriveClient.ReauthRequiredException) {
                    throw e
                } catch (e: Exception) {
                    stats.failedFiles++
                    if (stats.problems.size < 3) stats.problems.add("$childName: ${e.message ?: e.javaClass.simpleName}")
                    Log.e(TAG, "Upload failed for $childName", e)
                } finally {
                    cacheFile.delete()
                }
            }
        }
    }

    private fun matchesFilter(file: DocumentFile, folder: WatchedFolder): Boolean {
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
        private const val NOTIFICATION_ID = 4101

        const val KEY_MANUAL = "manual"
        const val KEY_CURRENT = "current_folder"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_FAILED = "failed"
        const val KEY_SUMMARY = "summary"

        const val PREFS = "svn_space_prefs"
        const val KEY_LAST_SUMMARY = "last_scan_summary"
        const val KEY_LAST_AT = "last_scan_at"
    }
}

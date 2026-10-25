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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
import space.svn.svnspace.util.AuthState
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
        val mediaTriggered = inputData.getString(KEY_TRIGGER) == "media"
        if (mediaTriggered) {
            // Content-trigger jobs fire once; line up the next one to run after this.
            try {
                BackupScheduler.scheduleMediaTrigger(appContext, fromWorker = true)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't re-arm the media trigger: ${e.message}")
            }
        }
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

        // Report right away and then every few seconds, so the dashboard always knows this
        // scan is alive (and can tell a stuck one apart), even during a long upload.
        phase = "Starting"
        report(force = true)
        val heartbeat = launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                report(force = true)
            }
        }
        try {
            // One scan at a time. Background scans (schedule, new files) skip when another is
            // running; Scan Now waits for it.
            if (!scanLock.tryLock()) {
                if (!manual) {
                    Log.i(TAG, "Another scan is running; skipping this background scan")
                    return@withContext Result.success()
                }
                phase = "Waiting for a background scan to finish"
                report(force = true)
                scanLock.lock()
            }
            try {
                runScan(manual)
            } finally {
                scanLock.unlock()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Anything not caught below (even an Error) still ends with a summary, so the
            // dashboard never keeps showing an old result.
            Log.e(TAG, "ScanAndBackupWorker crashed", t)
            stats.problems.add("Scan stopped by an error: ${t.message ?: t.javaClass.simpleName}")
            finish(Result.failure())
        } finally {
            heartbeat.cancel()
        }
    }

    private suspend fun runScan(manual: Boolean): Result {
        val repo = SvnRepository(appContext)
        try {
            val folders = repo.getEnabledWatchedFolders()
            val connections = repo.getEnabledConnections()
            Log.i(TAG, "Scanning ${folders.size} watched folders")

            if (folders.isEmpty()) {
                stats.problems.add("No watched folders turned on")
                return finish(Result.success())
            }
            if (!isOnline()) {
                stats.problems.add("No internet connection")
                return finish(Result.success())
            }

            for (original in folders) {
                var folder = original
                // Per-folder advanced options apply to scheduled scans; Scan Now always runs.
                if (!manual) {
                    val every = StorageAccess.folderIntervalMinutes(appContext, folder.id)
                    val last = folder.lastScanAt ?: 0L
                    if (every > 0 && System.currentTimeMillis() - last < every * 60_000L - 60_000L) continue
                    if (StorageAccess.pauseOnLowBattery(appContext, folder.id) && StorageAccess.isBatteryLow(appContext)) continue
                }
                currentFolder = folder.displayPath
                phase = "Scanning"
                report(force = true)

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
                // Each folder backs up to its one selected account and nowhere else. If that
                // account is gone or switched off, skip the folder rather than pick another.
                val conn = repo.getConnection(connId)
                if (conn == null) {
                    stats.noDestination++
                    continue
                }
                if (!conn.isEnabled) {
                    stats.problems.add("${folder.displayPath}: its account ${conn.displayName} is turned off – skipped")
                    continue
                }
                val secret = repo.getSecret(conn.id)
                if (secret == null) {
                    AuthState.markNeedsReconnect(appContext, conn.id, AuthState.Reason.MISSING_LOGIN)
                    stats.problems.add("${conn.displayName}: sign in again (Cloud Accounts → Reconnect)")
                    continue
                }
                val problem = StorageAccess.problemWith(appContext, folder.localUri)
                val root = if (problem == null) StorageAccess.rootOf(appContext, folder.localUri) else null
                if (root == null) {
                    stats.problems.add("${folder.displayPath}: ${problem ?: "can't open folder"}")
                    continue
                }

                val before = stats.uploaded
                val startedAt = System.currentTimeMillis()
                try {
                    val (target, remoteBase) = when (conn.type) {
                        ConnectionType.WEBDAV, ConnectionType.CUSTOM_HTTP ->
                            webDavTarget(folder, conn, secret)
                        ConnectionType.OAUTH_GOOGLE ->
                            driveTarget(folder, conn, secret, folderNameFor(folder, root))
                        else -> {
                            stats.problems.add("${conn.displayName}: uploads not supported yet")
                            continue
                        }
                    }
                    if (StorageAccess.isFileUri(folder.localUri)) {
                        // Direct file access: incremental by modified time, with a full pass
                        // once a day to catch files that arrived with old timestamps.
                        val lastComplete = StorageAccess.lastCompleteScan(appContext, folder.id)
                        val full = manual || lastComplete == 0L ||
                            startedAt - StorageAccess.lastFullScan(appContext, folder.id) > FULL_SCAN_EVERY_MS
                        val cutoff = if (full) 0L else lastComplete - MTIME_SLACK_MS
                        val matcher = StorageAccess.ExcludeMatcher(StorageAccess.excludes(appContext, folder.id))
                        val rootDir = StorageAccess.fileOf(folder.localUri)
                        walkFiles(repo, rootDir, "", target, remoteBase, folder, conn.id, matcher, cutoff,
                            StorageAccess.isInternalRoot(folder.localUri))
                        if (!isStopped) StorageAccess.markScanComplete(appContext, folder.id, startedAt, full)
                    } else {
                        walkAndUpload(repo, root, target, remoteBase, folder, conn.id)
                    }
                    stats.foldersScanned++
                    if (AuthState.reasonFor(appContext, conn.id) == AuthState.Reason.EXPIRED) {
                        AuthState.clear(appContext, conn.id)
                    }
                } catch (e: GoogleDriveClient.ReauthRequiredException) {
                    Log.w(TAG, "Drive login for ${conn.displayName} needs reconnecting: ${e.message}")
                    val reason = if (e is GoogleDriveClient.ScopeUpgradeRequiredException) AuthState.Reason.LIMITED_SCOPE
                    else AuthState.Reason.EXPIRED
                    AuthState.markNeedsReconnect(appContext, conn.id, reason)
                    stats.problems.add("${conn.displayName}: Google sign-in expired – Cloud Accounts → Reconnect")
                } catch (e: WebDavClient.AuthException) {
                    AuthState.markNeedsReconnect(appContext, conn.id, AuthState.Reason.MISSING_LOGIN)
                    stats.problems.add("${conn.displayName}: the server refused the login – Cloud Accounts → Reconnect")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Folder ${folder.displayPath} failed", e)
                    stats.problems.add("${folder.displayPath}: ${e.message ?: e.javaClass.simpleName}")
                }
                val now = System.currentTimeMillis()
                // Re-read so settings changed during a long scan aren't overwritten.
                val latest = repo.getEnabledWatchedFolders().firstOrNull { it.id == folder.id } ?: folder
                repo.saveWatchedFolder(
                    latest.copy(
                        connectionId = latest.connectionId ?: folder.connectionId,
                        lastScanAt = now,
                        lastBackupAt = if (stats.uploaded > before) now else latest.lastBackupAt
                    )
                )
            }
            return finish(Result.success())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ScanAndBackupWorker failed", e)
            stats.problems.add(e.message ?: e.javaClass.simpleName)
            return finish(Result.failure())
        }
    }

    @Volatile private var phase = "Starting"
    @Volatile private var currentFolder: String? = null
    @Volatile private var checked = 0
    @Volatile private var lastReport = 0L

    /** Progress for the dashboard: what it's doing, how far, and a heartbeat time. */
    private suspend fun report(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastReport < 1_000) return
        lastReport = now
        try {
            setProgress(workDataOf(
                KEY_PHASE to phase,
                KEY_CURRENT to currentFolder,
                KEY_UPLOADED to stats.uploaded,
                KEY_CHECKED to checked,
                KEY_HEARTBEAT to now
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't report progress: ${e.message}")
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

    private fun folderNameFor(folder: WatchedFolder, root: DocumentFile): String = when {
        StorageAccess.isInternalRoot(folder.localUri) -> "Internal storage"
        else -> root.name ?: folder.displayPath.substringAfterLast('/').ifBlank { "Backup" }
    }

    private fun driveTarget(
        folder: WatchedFolder,
        conn: Connection,
        refreshToken: String,
        folderName: String
    ): Pair<RemoteTarget, String> {
        val drive = GoogleDriveClient(OAuthHelper.GOOGLE_CLIENT_ID, refreshToken)
        val remoteBase = (folder.remotePath?.takeIf { it.isNotBlank() && it != "/" }
            ?: "${conn.basePath ?: GoogleDriveClient.DEFAULT_ROOT}/$folderName").trimEnd('/')
        drive.ensureFolder(remoteBase)
        val target = object : RemoteTarget {
            override fun mkdir(path: String) { drive.ensureFolder(path) }
            override fun upload(file: File, path: String) { drive.upload(file, path) }
        }
        return target to remoteBase
    }

    private fun webDavTarget(
        folder: WatchedFolder,
        conn: Connection,
        password: String
    ): Pair<RemoteTarget, String> {
        // Address + Path field (e.g. http://nas:5244 + /dav), joined the same way everywhere.
        val client = WebDavClient.forConnection(conn, password)
        // Paths are relative to that address; "automatic" is its top folder.
        val remoteBase = (folder.remotePath ?: "/").trimEnd('/')
        try {
            client.mkcols(remoteBase)
        } catch (e: WebDavClient.AuthException) {
            throw e
        } catch (_: Exception) { /* may already exist */ }

        val target = object : RemoteTarget {
            override fun mkdir(path: String) { client.mkcols(path) }
            override fun upload(file: File, path: String) { client.upload(file, path) }
        }
        return target to remoteBase
    }

    /**
     * Walk for folders read with All files access (plain paths). Uploads straight from
     * the file (no cache copy), skips excluded names and Android/data + Android/obb, and
     * only looks at files modified after [cutoff] (0 = everything).
     */
    private suspend fun walkFiles(
        repo: SvnRepository,
        dir: File,
        relDir: String,
        client: RemoteTarget,
        remoteDir: String,
        folder: WatchedFolder,
        connectionId: String,
        matcher: StorageAccess.ExcludeMatcher,
        cutoff: Long,
        isStorageRoot: Boolean
    ) {
        if (isStopped) return
        val children = dir.listFiles() ?: return
        var madeRemoteDir = relDir.isEmpty()
        for (child in children) {
            if (isStopped) return
            val name = child.name
            val rel = if (relDir.isEmpty()) name else "$relDir/$name"
            if (isStorageRoot && StorageAccess.ALWAYS_SKIPPED.any { rel.equals(it, true) || rel.startsWith("$it/", true) }) continue
            if (name.startsWith(".pending-") || name.startsWith(".trashed-")) continue
            if (matcher.excluded(name, rel)) continue
            if (child.isDirectory) {
                walkFiles(repo, child, rel, client, "$remoteDir/$name", folder, connectionId, matcher, cutoff, isStorageRoot)
                continue
            }
            if (!child.isFile || !matchesName(name, folder)) continue
            if (cutoff > 0 && child.lastModified() < cutoff) continue

            checked++
            report()
            val uri = android.net.Uri.fromFile(child).toString()
            val existing = repo.findRecord(uri, connectionId)
            if (existing != null && existing.verified && existing.sizeBytes == child.length() &&
                existing.backedUpAt >= child.lastModified()
            ) {
                stats.alreadyBackedUp++
                continue
            }
            try {
                // Only create remote folders that end up holding something.
                if (!madeRemoteDir) {
                    try { client.mkdir(remoteDir) } catch (_: Exception) {}
                    madeRemoteDir = true
                }
                val remotePath = "$remoteDir/$name"
                client.upload(child, remotePath)
                saveUploaded(repo, existing?.id, uri, rel, connectionId, remotePath, child, child.length(), folder)
            } catch (e: GoogleDriveClient.ReauthRequiredException) {
                throw e
            } catch (e: WebDavClient.AuthException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stats.failedFiles++
                if (stats.problems.size < 3) stats.problems.add("$name: ${e.message ?: e.javaClass.simpleName}")
                Log.e(TAG, "Upload failed for $rel", e)
            }
        }
    }

    /** Saves the record first, then counts it, so the dashboard counter and progress always agree. */
    private suspend fun saveUploaded(
        repo: SvnRepository,
        existingId: String?,
        uri: String,
        localPath: String,
        connectionId: String,
        remotePath: String,
        contents: File,
        size: Long,
        folder: WatchedFolder
    ) {
        val record = BackupRecord(
            id = existingId ?: UUID.randomUUID().toString(),
            localUri = uri,
            localPath = localPath,
            remoteConnectionId = connectionId,
            remotePath = remotePath,
            contentHash = sha256(contents),
            sizeBytes = size,
            verified = true
        )
        repo.saveRecord(record)
        stats.uploaded++
        report(force = true)
        Log.i(TAG, "Backed up: $localPath → $remotePath")
    }

    private suspend fun walkAndUpload(
        repo: SvnRepository,
        dir: DocumentFile,
        client: RemoteTarget,
        remoteDir: String,
        folder: WatchedFolder,
        connectionId: String,
        topDir: DocumentFile = dir
    ) {
        if (isStopped) return
        val children = dir.listFiles()
        // The top folder was made before the walk; sub-folders are only made on the server
        // when a file in them needs uploading (no network call per folder otherwise).
        var madeRemoteDir = dir == topDir
        for (child in children) {
            if (isStopped) return
            val childName = child.name ?: continue
            // Half-finished downloads and trashed items aren't real files yet.
            if (childName.startsWith(".pending-") || childName.startsWith(".trashed-")) continue
            if (child.isDirectory) {
                walkAndUpload(repo, child, client, "$remoteDir/$childName", folder, connectionId, topDir)
            } else if (child.isFile && matchesFilter(child, folder)) {
                checked++
                report()
                val existing = repo.findRecord(child.uri.toString(), connectionId)
                if (existing != null && existing.verified && existing.sizeBytes == child.length() &&
                    existing.backedUpAt >= child.lastModified()
                ) {
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

                    if (!madeRemoteDir) {
                        try { client.mkdir(remoteDir) } catch (_: Exception) {}
                        madeRemoteDir = true
                    }
                    val remotePath = "$remoteDir/$childName"
                    client.upload(cacheFile, remotePath)
                    saveUploaded(repo, existing?.id, child.uri.toString(), childName, connectionId,
                        remotePath, cacheFile, child.length(), folder)
                } catch (e: GoogleDriveClient.ReauthRequiredException) {
                    throw e
                } catch (e: WebDavClient.AuthException) {
                    throw e
                } catch (e: CancellationException) {
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
        val name = file.name ?: return false
        return matchesName(name, folder)
    }

    private fun matchesName(fileName: String, folder: WatchedFolder): Boolean {
        val name = fileName.lowercase()
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
        const val KEY_TRIGGER = "trigger"
        /** Incremental scans of plain-path folders do a full pass at least this often. */
        private const val FULL_SCAN_EVERY_MS = 24 * 60 * 60 * 1000L
        /** Files can land with a modified time slightly before the scan that should catch them. */
        private const val MTIME_SLACK_MS = 2 * 60 * 1000L
        const val KEY_CURRENT = "current_folder"
        const val KEY_PHASE = "phase"
        const val KEY_CHECKED = "checked"
        /** Time of the scan's last sign of life; the dashboard calls a scan stuck when it's old. */
        const val KEY_HEARTBEAT = "heartbeat"
        const val HEARTBEAT_MS = 15_000L
        /** No heartbeat for this long = the scan is stuck (it reports every 15 s while alive). */
        const val STALE_MS = 2 * 60 * 1000L

        /** One scan at a time in this app. */
        private val scanLock = Mutex()
        const val KEY_UPLOADED = "uploaded"
        const val KEY_FAILED = "failed"
        const val KEY_SUMMARY = "summary"

        const val PREFS = "svn_space_prefs"
        const val KEY_LAST_SUMMARY = "last_scan_summary"
        const val KEY_LAST_AT = "last_scan_at"
    }
}

package space.svn.svnspace.util

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import space.svn.svnspace.BuildConfig
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.model.ConnectionType
import space.svn.svnspace.data.model.FileFilter
import space.svn.svnspace.data.model.WatchedFolder
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.worker.BackupScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export / import of the app's settings as one JSON file:
 * scan interval, theme, cloud accounts (connections) and watched folders with their
 * per-folder settings (account, cloud folder, file types, interval, low-battery pause, excludes).
 *
 * Passwords and Google sign-ins are never written to the file. After an import, any account
 * without a saved login on this phone is marked "Login needed – tap Reconnect".
 */
object SettingsBackup {

    const val APP_MARKER = "svn-space-settings"
    const val FORMAT = 1
    private const val MAX_BYTES = 5 * 1024 * 1024

    class BadFileException(message: String) : Exception(message)

    /** A file that was read and checked, ready to show in the confirm step and to apply. */
    class Parsed(
        val exportedAt: String?,
        val appVersion: String?,
        val scanIntervalMinutes: Long?,
        val theme: ThemeManager.ThemeOption?,
        val connections: List<Connection>,
        val folders: List<FolderEntry>,
        val skipped: List<String>
    )

    class FolderEntry(
        val folder: WatchedFolder,
        val intervalMinutes: Long,
        val pauseOnLowBattery: Boolean,
        val excludes: List<String>
    )

    /** What happened, for the message after an import. */
    class ImportReport(
        val connections: Int,
        val folders: Int,
        val needLogin: List<String>,
        val foldersNeedingAccess: List<String>,
        val notes: List<String>
    )

    fun suggestedFileName(): String =
        "svn-space-settings-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

    // ---------------------------------------------------------------- export

    suspend fun export(context: Context, target: Uri): String {
        val repo = SvnRepository(context)
        val connections = repo.observeConnections().first()
        val folders = repo.observeWatchedFolders().first()

        val root = JSONObject()
        root.put("app", APP_MARKER)
        root.put("format", FORMAT)
        root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
        root.put("appVersion", BuildConfig.VERSION_NAME)
        root.put("secretsIncluded", false)
        root.put("note", "Passwords and sign-ins are not in this file. Reconnect each account after importing.")

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        root.put("settings", JSONObject().apply {
            put("scanIntervalMinutes", prefs.getString(BackupScheduler.PREF_INTERVAL, "60")?.toLongOrNull() ?: 60L)
            put("theme", ThemeManager.currentTheme(context).key)
        })

        root.put("connections", JSONArray().apply {
            connections.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("displayName", c.displayName)
                    put("type", c.type.name)
                    putOpt("username", c.username)
                    putOpt("host", c.host)
                    putOpt("port", c.port)
                    putOpt("basePath", c.basePath)
                    put("useTls", c.useTls)
                    put("isOAuth", c.isOAuth)
                    putOpt("providerHint", c.providerHint)
                    put("isEnabled", c.isEnabled)
                })
            }
        })

        root.put("watchedFolders", JSONArray().apply {
            folders.forEach { f ->
                put(JSONObject().apply {
                    put("id", f.id)
                    put("localUri", f.localUri)
                    put("displayPath", f.displayPath)
                    put("isEnabled", f.isEnabled)
                    putOpt("connectionId", f.connectionId)
                    putOpt("remotePath", f.remotePath)
                    put("fileFilter", f.fileFilter.name)
                    put("customExtensions", JSONArray(f.customExtensions))
                    put("intervalMinutes", StorageAccess.folderIntervalMinutes(context, f.id))
                    put("pauseOnLowBattery", StorageAccess.pauseOnLowBattery(context, f.id))
                    put("excludes", JSONArray(StorageAccess.excludes(context, f.id)))
                })
            }
        })

        val out = (runCatching { context.contentResolver.openOutputStream(target, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(target))
            ?: throw java.io.IOException("Couldn't open the file for writing")
        out.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
        return "${connections.size} cloud account(s) and ${folders.size} watched folder(s)"
    }

    // ---------------------------------------------------------------- import

    /** Reads and checks a file. Throws [BadFileException] with a plain-words reason. */
    fun read(context: Context, source: Uri): Parsed {
        val bytes = try {
            context.contentResolver.openInputStream(source)?.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_BYTES) throw BadFileException("That file is too big to be an svn-space settings file.")
                }
                buf.toByteArray()
            } ?: throw BadFileException("Couldn't open that file.")
        } catch (e: BadFileException) {
            throw e
        } catch (e: Exception) {
            throw BadFileException("Couldn't read that file: ${e.message ?: e.javaClass.simpleName}")
        }
        return parse(String(bytes, Charsets.UTF_8))
    }

    fun parse(text: String): Parsed {
        val root = try {
            JSONObject(text.trim().removePrefix("\uFEFF"))
        } catch (e: JSONException) {
            throw BadFileException("That isn't an svn-space settings file (it couldn't be read).")
        }
        if (root.optString("app") != APP_MARKER) {
            throw BadFileException("That file wasn't made by svn-space's Export settings.")
        }
        val format = root.optInt("format", 0)
        if (format < 1) throw BadFileException("That settings file is damaged (no format number).")
        if (format > FORMAT) {
            throw BadFileException("That file was made by a newer version of svn-space. Update the app, then import it.")
        }

        val skipped = mutableListOf<String>()
        val settings = root.optJSONObject("settings")
        val interval = settings?.optLong("scanIntervalMinutes", -1L)?.takeIf { it >= 0 }
        val theme = settings?.optString("theme")?.takeIf { it.isNotBlank() }?.let { key ->
            ThemeManager.ThemeOption.entries.find { it.key == key }.also {
                if (it == null) skipped += "Theme \"$key\" isn't in this version, so the theme stays as it is."
            }
        }

        val connections = mutableListOf<Connection>()
        root.optJSONArray("connections")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() }
                val name = o.optString("displayName").ifBlank { "Account ${i + 1}" }
                val type = runCatching { ConnectionType.valueOf(o.optString("type")) }.getOrNull()
                if (id == null || type == null) {
                    skipped += "Cloud account \"$name\" was skipped (unknown or missing type)."
                    continue
                }
                connections += Connection(
                    id = id,
                    displayName = name,
                    type = type,
                    username = o.optStringOrNull("username"),
                    host = o.optStringOrNull("host"),
                    port = if (o.has("port") && !o.isNull("port")) o.optInt("port").takeIf { it in 1..65535 } else null,
                    basePath = o.optStringOrNull("basePath"),
                    useTls = o.optBoolean("useTls", true),
                    isOAuth = o.optBoolean("isOAuth", false),
                    providerHint = o.optStringOrNull("providerHint"),
                    isEnabled = o.optBoolean("isEnabled", true)
                )
            }
        }

        val folders = mutableListOf<FolderEntry>()
        root.optJSONArray("watchedFolders")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() }
                val uri = o.optString("localUri").takeIf { it.isNotBlank() }
                val display = o.optString("displayPath").ifBlank { uri ?: "Folder ${i + 1}" }
                if (id == null || uri == null) {
                    skipped += "Watched folder \"$display\" was skipped (no folder location)."
                    continue
                }
                val filter = runCatching { FileFilter.valueOf(o.optString("fileFilter")) }.getOrDefault(FileFilter.ANY)
                folders += FolderEntry(
                    folder = WatchedFolder(
                        id = id,
                        localUri = uri,
                        displayPath = display,
                        isEnabled = o.optBoolean("isEnabled", true),
                        connectionId = o.optStringOrNull("connectionId"),
                        remotePath = o.optStringOrNull("remotePath"),
                        fileFilter = filter,
                        customExtensions = o.optJSONArray("customExtensions").strings()
                    ),
                    intervalMinutes = o.optLong("intervalMinutes", 0L).coerceAtLeast(0L),
                    pauseOnLowBattery = o.optBoolean("pauseOnLowBattery", true),
                    excludes = o.optJSONArray("excludes").strings()
                )
            }
        }

        return Parsed(
            exportedAt = root.optStringOrNull("exportedAt"),
            appVersion = root.optStringOrNull("appVersion"),
            scanIntervalMinutes = interval,
            theme = theme,
            connections = connections,
            folders = folders,
            skipped = skipped
        )
    }

    /**
     * Applies a parsed file. Accounts and folders are added, or updated when one with the same
     * ID is already here; nothing on the phone is deleted. Saved logins on this phone are kept.
     */
    suspend fun apply(context: Context, parsed: Parsed): ImportReport {
        val repo = SvnRepository(context)
        val notes = parsed.skipped.toMutableList()
        val needLogin = mutableListOf<String>()

        for (c in parsed.connections) {
            repo.saveConnection(c) // no secret: keeps a login already saved for this ID
            if (repo.getSecret(c.id).isNullOrEmpty()) {
                AuthState.markNeedsReconnect(context, c.id, AuthState.Reason.MISSING_LOGIN)
                needLogin += c.displayName
            }
        }

        val knownConnections = repo.observeConnections().first().map { it.id }.toSet()
        val needAccess = mutableListOf<String>()
        for (entry in parsed.folders) {
            var f = entry.folder
            if (f.connectionId != null && f.connectionId !in knownConnections) {
                notes += "\"${f.displayPath}\" pointed to an account that isn't in the file, so it has no backup destination now."
                f = f.copy(connectionId = null, remotePath = null)
            }
            repo.saveWatchedFolder(f)
            StorageAccess.saveFolderOptions(context, f.id, entry.intervalMinutes, entry.pauseOnLowBattery)
            StorageAccess.saveExcludes(context, f.id, entry.excludes)
            if (StorageAccess.problemWith(context, f.localUri) != null) needAccess += f.displayPath
        }

        parsed.theme?.let { ThemeManager.setTheme(context, it) }
        parsed.scanIntervalMinutes?.let { BackupScheduler.updateInterval(context, it) }

        return ImportReport(parsed.connections.size, parsed.folders.size, needLogin, needAccess, notes)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optString(i).trim().takeIf { it.isNotEmpty() } }
    }
}

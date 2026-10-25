package space.svn.svnspace.data.remote

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Google Drive v3 client for one connected Google account.
 *
 * Auth: the app is a public (installed) OAuth client, so no client secret.
 * We keep the refresh token per connection and trade it for short-lived
 * access tokens here. The scope is the full "drive" scope, so the app sees
 * everything in the account (My Drive, Shared with me, shared drives).
 * Connections made before that used drive.file and only see files the app
 * created; [grantedFullDrive] says which kind a login is.
 *
 * Two ways in:
 *  - path API (backups): slash paths like "/svn-space/Camera"; "/" is My Drive's root.
 *  - ID API (cloud browser): folder IDs, paging with nextPageToken, file management.
 */
class GoogleDriveClient(
    private val clientId: String,
    private val refreshToken: String
) {
    /** The login expired or was revoked: the user has to connect the account again. */
    open class ReauthRequiredException(message: String) : IOException(message)

    /** The login works but was made with the old drive.file scope, so it can't see everything. */
    class ScopeUpgradeRequiredException(message: String) : ReauthRequiredException(message)

    data class DriveEntry(
        val id: String,
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long
    )

    /** One file or folder as the cloud browser shows it. */
    data class DriveItem(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val modifiedAt: Long,
        val webViewLink: String?,
        val parents: List<String>,
        val driveId: String?,
        val canRename: Boolean,
        val canTrash: Boolean,
        val canAddChildren: Boolean,
        val canMove: Boolean,
        /** For shortcuts: the file or folder it points to. */
        val targetId: String? = null,
        val targetMimeType: String? = null
    ) {
        val isFolder get() = (targetMimeType ?: mimeType) == FOLDER_MIME
        val isShortcut get() = mimeType == SHORTCUT_MIME
        /** Docs, Sheets, Slides etc.: no bytes to download, only export or open in the browser. */
        val isGoogleType get() = mimeType.startsWith("application/vnd.google-apps.") && !isFolder && !isShortcut
    }

    data class DrivePage(val items: List<DriveItem>, val nextPageToken: String?)

    /** A shared drive, shown as a folder at the top level. */
    data class SharedDrive(val id: String, val name: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private var accessToken: String? = null
    private var accessExpiresAt: Long = 0
    private var grantedScopes: String? = null
    private val folderIdCache = HashMap<String, String>()

    // ---- Auth ----

    @Synchronized
    private fun token(): String {
        val now = System.currentTimeMillis()
        accessToken?.let { if (now < accessExpiresAt - 60_000) return it }
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val req = Request.Builder().url(TOKEN_URL).post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (text.contains("invalid_grant") || text.contains("unauthorized_client") || resp.code == 401) {
                    throw ReauthRequiredException(
                        "Google sign-in expired or was revoked. Connect this Google account again."
                    )
                }
                throw IOException("Token refresh failed (${resp.code}): $text")
            }
            val json = JsonParser.parseString(text).asJsonObject
            val tok = json.get("access_token").asString
            val expiresIn = json.get("expires_in")?.asLong ?: 3600L
            grantedScopes = json.get("scope")?.asString
            accessToken = tok
            accessExpiresAt = now + expiresIn * 1000
            return tok
        }
    }

    @Synchronized
    private fun dropAccessToken() {
        accessToken = null
        accessExpiresAt = 0
    }

    /**
     * True when the current login has the full Drive scope, false for an old drive.file
     * login, null when it couldn't be checked (offline). Throws [ReauthRequiredException]
     * when the login no longer works at all.
     */
    fun grantedFullDrive(): Boolean? {
        if (grantedScopes == null) {
            try {
                token()
            } catch (e: ReauthRequiredException) {
                throw e
            } catch (e: Exception) {
                return null
            }
        }
        val scopes = grantedScopes ?: return null
        return scopes.split(' ').any { it == FULL_DRIVE_SCOPE }
    }

    /** Runs a request with a fresh token: one retry on 401, clear errors for auth and scope problems. */
    private fun execute(builder: Request.Builder): Response {
        var resp = http.newCall(builder.header("Authorization", "Bearer ${token()}").build()).execute()
        if (resp.code == 401) {
            resp.close()
            dropAccessToken()
            resp = http.newCall(builder.header("Authorization", "Bearer ${token()}").build()).execute()
            if (resp.code == 401) {
                resp.close()
                throw ReauthRequiredException(
                    "Google sign-in expired or was revoked. Connect this Google account again."
                )
            }
        }
        if (resp.code == 403) {
            val text = resp.peekBody(64 * 1024).string()
            if (text.contains("insufficientPermissions") || text.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT") ||
                text.contains("insufficient authentication scopes", ignoreCase = true)
            ) {
                resp.close()
                throw ScopeUpgradeRequiredException(
                    "This Google account was connected with limited access. Reconnect it to see all your Drive files."
                )
            }
        }
        return resp
    }

    private fun call(builder: Request.Builder): String {
        execute(builder).use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException("Drive error ${it.code}: ${driveMessage(text)}")
            return text
        }
    }

    /** Pulls Google's human-readable message out of an error body. */
    private fun driveMessage(text: String): String = try {
        JsonParser.parseString(text).asJsonObject.getAsJsonObject("error")?.get("message")?.asString ?: text
    } catch (e: Exception) {
        text.take(300)
    }

    /** Quick check that the stored login still works. */
    fun testConnection(): Boolean = try {
        call(Request.Builder().url("$API/about?fields=user(emailAddress)").get())
        true
    } catch (e: ReauthRequiredException) {
        throw e
    } catch (e: Exception) {
        false
    }

    // ---- Folders (path API, used by backups) ----

    private fun normalize(path: String): String {
        val parts = path.split('/').filter { it.isNotBlank() }
        return "/" + parts.joinToString("/")
    }

    private fun escape(name: String) = name.replace("\\", "\\\\").replace("'", "\\'")

    private fun findChild(parentId: String, name: String, folder: Boolean?): String? {
        val q = buildString {
            append("name = '${escape(name)}' and '$parentId' in parents and trashed = false")
            when (folder) {
                true -> append(" and mimeType = '$FOLDER_MIME'")
                false -> append(" and mimeType != '$FOLDER_MIME'")
                null -> {}
            }
        }
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("pageSize", "10")
            .build()
        val json = JsonParser.parseString(call(Request.Builder().url(url).get())).asJsonObject
        val files = json.getAsJsonArray("files") ?: return null
        return if (files.size() > 0) files[0].asJsonObject.get("id").asString else null
    }

    private fun createFolder(parentId: String, name: String): String {
        val meta = """{"name":${quote(name)},"mimeType":"$FOLDER_MIME","parents":["$parentId"]}"""
        val text = call(
            Request.Builder().url("$API/files?fields=id")
                .post(meta.toRequestBody(JSON))
        )
        return JsonParser.parseString(text).asJsonObject.get("id").asString
    }

    /** Returns the folder ID for [path], creating any missing folders. */
    @Synchronized
    fun ensureFolder(path: String): String {
        val norm = normalize(path)
        if (norm == "/") return "root"
        folderIdCache[norm]?.let { return it }
        var parentId = "root"
        var current = ""
        for (part in norm.split('/').filter { it.isNotBlank() }) {
            current += "/$part"
            val cached = folderIdCache[current]
            parentId = cached ?: (findChild(parentId, part, true) ?: createFolder(parentId, part))
            folderIdCache[current] = parentId
        }
        return parentId
    }

    // ---- Files (path API) ----

    /** Uploads [file] to [remotePath] (folder path + file name). Replaces an existing copy. */
    fun upload(file: File, remotePath: String, mimeType: String = "application/octet-stream"): String {
        val norm = normalize(remotePath)
        val name = norm.substringAfterLast('/')
        val parentId = ensureFolder(norm.substringBeforeLast('/', "/"))
        val existingId = findChild(parentId, name, false)
        val meta = if (existingId != null) "{}" else """{"name":${quote(name)},"parents":["$parentId"]}"""
        return resumableUpload(meta, file, mimeType, existingId).id
    }

    /**
     * Resumable upload session. Works for any size; Google's multipart upload is only
     * meant for files up to 5 MB. [existingId] replaces that file's contents.
     */
    private fun resumableUpload(metaJson: String, file: File, mimeType: String, existingId: String?): DriveItem {
        val startUrl = if (existingId != null) {
            "$UPLOAD/files/$existingId?uploadType=resumable&supportsAllDrives=true&fields=$ITEM_FIELDS"
        } else {
            "$UPLOAD/files?uploadType=resumable&supportsAllDrives=true&fields=$ITEM_FIELDS"
        }
        val start = Request.Builder().url(startUrl)
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", file.length().toString())
            .method(if (existingId != null) "PATCH" else "POST", metaJson.toRequestBody(JSON))
        val session = execute(start).use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Drive upload start failed (${resp.code}): ${driveMessage(resp.body?.string().orEmpty())}")
            }
            resp.header("Location") ?: throw IOException("Drive didn't return an upload session")
        }
        val put = Request.Builder().url(session).put(file.asRequestBody(mimeType.toMediaType()))
        val text = execute(put).use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Drive upload failed (${resp.code}): ${driveMessage(body)}")
            body
        }
        val item = parseItem(JsonParser.parseString(text).asJsonObject)
        if (item.size >= 0 && item.size != file.length()) {
            throw IOException("Drive stored ${item.size} bytes, expected ${file.length()}")
        }
        return item
    }

    /** Lists the children of the folder at [path]. */
    fun list(path: String): List<DriveEntry> {
        val norm = normalize(path)
        val folderId = if (norm == "/") "root" else ensureFolder(norm)
        val out = ArrayList<DriveEntry>()
        var pageToken: String? = null
        do {
            val page = listChildren(folderId, null, pageToken)
            page.items.forEach { item ->
                val childPath = if (norm == "/") "/${item.name}" else "$norm/${item.name}"
                if (item.isFolder) folderIdCache[childPath] = item.targetId ?: item.id
                out.add(DriveEntry(item.id, item.name, childPath, item.isFolder, item.size.coerceAtLeast(0)))
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return out
    }

    // ---- Cloud browser (ID API) ----

    private fun parseItem(o: JsonObject): DriveItem {
        val caps = o.getAsJsonObject("capabilities")
        fun cap(name: String) = caps?.get(name)?.asBoolean ?: true
        val shortcut = o.getAsJsonObject("shortcutDetails")
        return DriveItem(
            id = o.get("id").asString,
            name = o.get("name")?.asString ?: "(no name)",
            mimeType = o.get("mimeType")?.asString ?: "application/octet-stream",
            size = o.get("size")?.asLong ?: -1L,
            modifiedAt = o.get("modifiedTime")?.asString?.let { parseRfc3339(it) } ?: 0L,
            webViewLink = o.get("webViewLink")?.asString,
            parents = o.getAsJsonArray("parents")?.map { it.asString } ?: emptyList(),
            driveId = o.get("driveId")?.asString,
            canRename = cap("canRename"),
            canTrash = cap("canTrash"),
            canAddChildren = cap("canAddChildren"),
            canMove = cap("canMoveItemWithinDrive"),
            targetId = shortcut?.get("targetId")?.asString,
            targetMimeType = shortcut?.get("targetMimeType")?.asString
        )
    }

    private fun parseRfc3339(s: String): Long = try {
        java.time.Instant.parse(s).toEpochMilli()
    } catch (e: Exception) {
        0L
    }

    private fun listQuery(q: String, driveId: String?, pageToken: String?, orderBy: String): DrivePage {
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "nextPageToken,files($ITEM_FIELDS)")
            .addQueryParameter("orderBy", orderBy)
            .addQueryParameter("pageSize", "100")
            .addQueryParameter("supportsAllDrives", "true")
            .addQueryParameter("includeItemsFromAllDrives", "true")
            .apply {
                if (driveId != null) {
                    addQueryParameter("corpora", "drive")
                    addQueryParameter("driveId", driveId)
                } else {
                    addQueryParameter("corpora", "user")
                }
                pageToken?.let { addQueryParameter("pageToken", it) }
            }
            .build()
        val json = JsonParser.parseString(call(Request.Builder().url(url).get())).asJsonObject
        val items = json.getAsJsonArray("files")?.map { parseItem(it.asJsonObject) } ?: emptyList()
        return DrivePage(items, json.get("nextPageToken")?.asString)
    }

    /**
     * One page of a folder's children. "root" is My Drive; for a shared drive pass its ID
     * as both [folderId] (its root) and [driveId].
     */
    fun listChildren(folderId: String, driveId: String? = null, pageToken: String? = null): DrivePage =
        listQuery("'${escape(folderId)}' in parents and trashed = false", driveId, pageToken, "folder,name_natural")

    /** One page of "Shared with me". */
    fun listSharedWithMe(pageToken: String? = null): DrivePage =
        listQuery("sharedWithMe = true and trashed = false", null, pageToken, "folder,modifiedTime desc")

    /** The shared drives this account is a member of. */
    fun listSharedDrives(): List<SharedDrive> {
        val out = ArrayList<SharedDrive>()
        var pageToken: String? = null
        do {
            val url = "$API/drives".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "100")
                .addQueryParameter("fields", "nextPageToken,drives(id,name)")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = JsonParser.parseString(call(Request.Builder().url(url).get())).asJsonObject
            json.getAsJsonArray("drives")?.forEach {
                val o = it.asJsonObject
                out.add(SharedDrive(o.get("id").asString, o.get("name").asString))
            }
            pageToken = json.get("nextPageToken")?.asString
        } while (pageToken != null)
        return out
    }

    /** Downloads a normal (non-Google) file's bytes into [out]. */
    fun download(fileId: String, out: File, onProgress: ((Long, Long) -> Unit)? = null) {
        stream(Request.Builder().url("$API/files/$fileId?alt=media&supportsAllDrives=true").get(), out, onProgress)
    }

    /** Exports a Google Doc / Sheet / Slides file as [exportMime] into [out]. Google caps exports at 10 MB. */
    fun export(fileId: String, exportMime: String, out: File, onProgress: ((Long, Long) -> Unit)? = null) {
        val url = "$API/files/$fileId/export".toHttpUrl().newBuilder()
            .addQueryParameter("mimeType", exportMime).build()
        stream(Request.Builder().url(url).get(), out, onProgress)
    }

    private fun stream(builder: Request.Builder, out: File, onProgress: ((Long, Long) -> Unit)?) {
        execute(builder).use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Drive download failed (${resp.code}): ${driveMessage(resp.body?.string().orEmpty())}")
            }
            val body = resp.body ?: throw IOException("Empty response")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress?.invoke(done, total)
                    }
                }
            }
        }
    }

    /** Uploads [file] as a new file named [name] inside [parentId]. */
    fun uploadInto(parentId: String, file: File, name: String, mimeType: String): DriveItem {
        val meta = """{"name":${quote(name)},"parents":[${quote(parentId)}]}"""
        return resumableUpload(meta, file, mimeType, null)
    }

    /** Creates a folder named [name] inside [parentId]. */
    fun createFolderIn(parentId: String, name: String): DriveItem {
        val meta = """{"name":${quote(name)},"mimeType":"$FOLDER_MIME","parents":[${quote(parentId)}]}"""
        val text = call(
            Request.Builder().url("$API/files?supportsAllDrives=true&fields=$ITEM_FIELDS")
                .post(meta.toRequestBody(JSON))
        )
        return parseItem(JsonParser.parseString(text).asJsonObject)
    }

    fun rename(fileId: String, newName: String): DriveItem {
        val text = call(
            Request.Builder().url("$API/files/$fileId?supportsAllDrives=true&fields=$ITEM_FIELDS")
                .patch("""{"name":${quote(newName)}}""".toRequestBody(JSON))
        )
        return parseItem(JsonParser.parseString(text).asJsonObject)
    }

    /** Moves a file or folder into [newParentId], taking it out of its current folders. */
    fun move(fileId: String, newParentId: String): DriveItem {
        val current = JsonParser.parseString(
            call(Request.Builder().url("$API/files/$fileId?supportsAllDrives=true&fields=parents").get())
        ).asJsonObject.getAsJsonArray("parents")?.map { it.asString } ?: emptyList()
        val url = "$API/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("supportsAllDrives", "true")
            .addQueryParameter("addParents", newParentId)
            .apply { if (current.isNotEmpty()) addQueryParameter("removeParents", current.joinToString(",")) }
            .addQueryParameter("fields", ITEM_FIELDS)
            .build()
        val text = call(Request.Builder().url(url).patch("{}".toRequestBody(JSON)))
        return parseItem(JsonParser.parseString(text).asJsonObject)
    }

    /** Moves to the Drive trash (recoverable for 30 days). Never a permanent delete. */
    fun trash(fileId: String) {
        call(
            Request.Builder().url("$API/files/$fileId?supportsAllDrives=true&fields=id")
                .patch("""{"trashed":true}""".toRequestBody(JSON))
        )
    }

    private fun quote(s: String): String = Gson().toJson(s)

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"
        const val FULL_DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
        /** Default backup root in each account's My Drive. */
        const val DEFAULT_ROOT = "/svn-space"
        private const val ITEM_FIELDS =
            "id,name,mimeType,size,modifiedTime,webViewLink,parents,driveId," +
                "capabilities(canRename,canTrash,canAddChildren,canMoveItemWithinDrive)," +
                "shortcutDetails(targetId,targetMimeType)"
        private val JSON = "application/json; charset=UTF-8".toMediaType()

        /** What a Google file exports to (MIME type, extension), or null when it can only be opened in the browser. */
        fun exportFormat(googleMime: String): Pair<String, String>? = when (googleMime) {
            "application/vnd.google-apps.document" ->
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx"
            "application/vnd.google-apps.spreadsheet" ->
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx"
            "application/vnd.google-apps.presentation" ->
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx"
            "application/vnd.google-apps.drawing" -> "image/png" to "png"
            else -> null
        }
    }
}

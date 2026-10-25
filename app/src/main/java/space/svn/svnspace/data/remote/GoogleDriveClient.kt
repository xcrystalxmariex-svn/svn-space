package space.svn.svnspace.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Small Google Drive v3 client for one connected Google account.
 *
 * Auth: the app is a public (installed) OAuth client, so no client secret.
 * We keep the refresh token per connection and trade it for short-lived
 * access tokens here. Scope is drive.file, so the app only sees files and
 * folders it created itself (its "svn-space" folder tree in My Drive).
 *
 * Paths are slash paths like "/svn-space/Camera"; "/" is My Drive's root.
 */
class GoogleDriveClient(
    private val clientId: String,
    private val refreshToken: String
) {
    class ReauthRequiredException(message: String) : IOException(message)

    data class DriveEntry(
        val id: String,
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private var accessToken: String? = null
    private var accessExpiresAt: Long = 0
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
                if (text.contains("invalid_grant")) {
                    throw ReauthRequiredException(
                        "Google sign-in expired or was revoked. Connect this Google account again."
                    )
                }
                throw IOException("Token refresh failed (${resp.code}): $text")
            }
            val json = JsonParser.parseString(text).asJsonObject
            val tok = json.get("access_token").asString
            val expiresIn = json.get("expires_in")?.asLong ?: 3600L
            accessToken = tok
            accessExpiresAt = now + expiresIn * 1000
            return tok
        }
    }

    private fun call(builder: Request.Builder): String {
        val resp = http.newCall(
            builder.header("Authorization", "Bearer ${token()}").build()
        ).execute()
        resp.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException("Drive error ${it.code}: $text")
            return text
        }
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

    // ---- Folders ----

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

    // ---- Files ----

    /** Uploads [file] to [remotePath] (folder path + file name). Replaces an existing copy. */
    fun upload(file: File, remotePath: String, mimeType: String = "application/octet-stream"): String {
        val norm = normalize(remotePath)
        val name = norm.substringAfterLast('/')
        val parentId = ensureFolder(norm.substringBeforeLast('/', "/"))
        val existingId = findChild(parentId, name, false)
        val media = file.asRequestBody(mimeType.toMediaType())

        val text = if (existingId != null) {
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart("{}".toRequestBody(JSON))
                .addPart(media)
                .build()
            call(
                Request.Builder()
                    .url("$UPLOAD/files/$existingId?uploadType=multipart&fields=id,size")
                    .patch(body)
            )
        } else {
            val meta = """{"name":${quote(name)},"parents":["$parentId"]}"""
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(meta.toRequestBody(JSON))
                .addPart(media)
                .build()
            call(
                Request.Builder()
                    .url("$UPLOAD/files?uploadType=multipart&fields=id,size")
                    .post(body)
            )
        }
        val json = JsonParser.parseString(text).asJsonObject
        val size = json.get("size")?.asLong ?: -1L
        if (size >= 0 && size != file.length()) {
            throw IOException("Drive stored $size bytes, expected ${file.length()}")
        }
        return json.get("id").asString
    }

    /** Lists the children of the folder at [path]. */
    fun list(path: String): List<DriveEntry> {
        val norm = normalize(path)
        val folderId = if (norm == "/") "root" else ensureFolder(norm)
        val out = ArrayList<DriveEntry>()
        var pageToken: String? = null
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "'$folderId' in parents and trashed = false")
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size)")
                .addQueryParameter("orderBy", "folder,name")
                .addQueryParameter("pageSize", "200")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = JsonParser.parseString(call(Request.Builder().url(url).get())).asJsonObject
            json.getAsJsonArray("files")?.forEach { el ->
                val o = el.asJsonObject
                val name = o.get("name").asString
                val isDir = o.get("mimeType").asString == FOLDER_MIME
                val childPath = if (norm == "/") "/$name" else "$norm/$name"
                if (isDir) folderIdCache[childPath] = o.get("id").asString
                out.add(
                    DriveEntry(
                        id = o.get("id").asString,
                        name = name,
                        path = childPath,
                        isDirectory = isDir,
                        size = o.get("size")?.asLong ?: 0L
                    )
                )
            }
            pageToken = json.get("nextPageToken")?.asString
        } while (pageToken != null)
        return out
    }

    private fun quote(s: String): String = Gson().toJson(s)

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        /** Default backup root in each account's My Drive. */
        const val DEFAULT_ROOT = "/svn-space"
        private val JSON = "application/json; charset=UTF-8".toMediaType()
    }
}

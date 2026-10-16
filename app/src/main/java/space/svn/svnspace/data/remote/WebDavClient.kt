package space.svn.svnspace.data.remote

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal but functional WebDAV client using OkHttp.
 * Supports basic auth, PROPFIND listing, PUT upload, DELETE, MKCOL.
 * Works with most standard WebDAV servers, Nextcloud, ownCloud, many NAS devices, TerraBox-style endpoints that speak WebDAV.
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val useTls: Boolean = true
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val credential = Credentials.basic(username, password)

    private fun url(path: String): String {
        val base = baseUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return base + p
    }

    /**
     * List children of a remote directory. Returns simple name + isDirectory pairs.
     * Uses Depth: 1 PROPFIND.
     */
    fun list(path: String = "/"): List<RemoteEntry> {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop>
            </d:propfind>""".trimIndent()

        val request = Request.Builder()
            .url(url(path))
            .header("Authorization", credential)
            .header("Depth", "1")
            .method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("PROPFIND failed: ${response.code} ${response.message}")
            }
            val xml = response.body?.string() ?: return emptyList()
            return parsePropfind(xml, path)
        }
    }

    /**
     * Upload a local file to the given remote path (full path including filename).
     */
    fun upload(localFile: File, remotePath: String) {
        val request = Request.Builder()
            .url(url(remotePath))
            .header("Authorization", credential)
            .put(localFile.asRequestBody("application/octet-stream".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("PUT failed: ${response.code} ${response.message}")
            }
        }
    }

    /**
     * Create a remote collection (directory).
     */
    fun mkcol(path: String) {
        val request = Request.Builder()
            .url(url(path))
            .header("Authorization", credential)
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()

        client.newCall(request).execute().use { response ->
            // 201 Created or 405 Method Not Allowed (already exists) are both acceptable
            if (response.code != 201 && response.code != 405 && response.code != 301) {
                throw IOException("MKCOL failed: ${response.code} ${response.message}")
            }
        }
    }

    /**
     * Delete a remote resource.
     */
    fun delete(path: String) {
        val request = Request.Builder()
            .url(url(path))
            .header("Authorization", credential)
            .delete()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                throw IOException("DELETE failed: ${response.code} ${response.message}")
            }
        }
    }

    /**
     * Simple existence / connectivity check.
     */
    fun testConnection(): Boolean {
        return try {
            list("/")
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun parsePropfind(xml: String, requestedPath: String): List<RemoteEntry> {
        // Extremely lightweight parser – good enough for standard responses.
        // For production you would use a proper XML pull parser.
        val entries = mutableListOf<RemoteEntry>()
        val hrefRegex = Regex("""<[^>]*href[^>]*>([^<]+)</[^>]*href>""", RegexOption.IGNORE_CASE)
        val collectionRegex = Regex("""<[^>]*collection[^/]*/>""", RegexOption.IGNORE_CASE)

        val blocks = xml.split(Regex("""</?d:response>|</?response>""", RegexOption.IGNORE_CASE))
        for (block in blocks) {
            val hrefMatch = hrefRegex.find(block) ?: continue
            var href = hrefMatch.groupValues[1].trim()
            // Decode simple percent encoding if present
            href = href.replace("%20", " ")
            val name = href.trimEnd('/').substringAfterLast('/')
            if (name.isBlank()) continue
            // Skip the directory itself
            if (href.trimEnd('/') == requestedPath.trimEnd('/') || name == requestedPath.trim('/').substringAfterLast('/')) continue
            val isDir = collectionRegex.containsMatchIn(block)
            entries.add(RemoteEntry(name = name, path = href, isDirectory = isDir))
        }
        return entries.distinctBy { it.path }
    }

    data class RemoteEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean
    )
}

package space.svn.svnspace.data.remote

import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * WebDAV client on OkHttp: PROPFIND listing (name, size, modified date, type),
 * GET download, PUT upload, MKCOL, MOVE (rename / move) and DELETE.
 * Works with standard WebDAV servers, Nextcloud, ownCloud and most NAS boxes.
 *
 * Paths are always relative to [baseUrl] ("/" is the base URL itself), and are
 * percent-encoded segment by segment, so names with spaces, #, ? or unicode work.
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    @Suppress("unused") private val useTls: Boolean = true
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val credential = Credentials.basic(username, password)

    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    /** Decoded path of the base URL, e.g. "/remote.php/dav/files/me" (no trailing slash). */
    private val basePath: String = "/" + base.pathSegments.filter { it.isNotEmpty() }.joinToString("/")

    private fun segments(path: String) = path.split('/').filter { it.isNotEmpty() }

    private fun url(path: String, trailingSlash: Boolean = false): HttpUrl {
        val b = base.newBuilder()
        // Drop the empty last segment a trailing slash leaves behind.
        if (base.pathSegments.lastOrNull() == "") b.removePathSegment(base.pathSegments.size - 1)
        segments(path).forEach { b.addPathSegment(it) }
        if (trailingSlash) b.addPathSegment("")
        return b.build()
    }

    private fun normalize(path: String) = "/" + segments(path).joinToString("/")

    /** Lists the children of a remote folder (PROPFIND, Depth 1). */
    fun list(path: String = "/"): List<RemoteEntry> {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getcontenttype/></d:prop>
            </d:propfind>""".trimIndent()

        val request = Request.Builder()
            .url(url(path, trailingSlash = normalize(path) != "/"))
            .header("Authorization", credential)
            .header("Depth", "1")
            .method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw AuthException("The server refused the username or password")
            if (!response.isSuccessful) {
                throw IOException("PROPFIND failed: ${response.code} ${response.message}")
            }
            val xml = response.body?.string() ?: return emptyList()
            return parsePropfind(xml, normalize(path))
        }
    }

    /** Uploads a local file to [remotePath] (folder + file name). */
    fun upload(localFile: File, remotePath: String, mimeType: String = "application/octet-stream") {
        val request = Request.Builder()
            .url(url(remotePath))
            .header("Authorization", credential)
            .put(localFile.asRequestBody(mimeType.toMediaTypeOrNull()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("PUT failed: ${response.code} ${response.message}")
            }
        }
    }

    /** Downloads [remotePath] into [out]. [onProgress] gets bytes so far and the total (-1 if unknown). */
    fun download(remotePath: String, out: File, onProgress: ((Long, Long) -> Unit)? = null) {
        val request = Request.Builder()
            .url(url(remotePath))
            .header("Authorization", credential)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: ${response.code} ${response.message}")
            val body = response.body ?: throw IOException("Empty response")
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

    /** Creates a remote folder. An existing folder is fine. */
    fun mkcol(path: String) {
        val request = Request.Builder()
            .url(url(path, trailingSlash = true))
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

    /** Moves or renames [fromPath] to [toPath]. Refuses to overwrite something already there. */
    fun move(fromPath: String, toPath: String, isDirectory: Boolean = false) {
        val request = Request.Builder()
            .url(url(fromPath, trailingSlash = isDirectory))
            .header("Authorization", credential)
            .header("Destination", url(toPath, trailingSlash = isDirectory).toString())
            .header("Overwrite", "F")
            .method("MOVE", null)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 412) throw IOException("Something with that name is already there")
            if (!response.isSuccessful) throw IOException("MOVE failed: ${response.code} ${response.message}")
        }
    }

    /** Deletes a remote file or folder (folders are deleted with everything in them). */
    fun delete(path: String, isDirectory: Boolean = false) {
        val request = Request.Builder()
            .url(url(path, trailingSlash = isDirectory))
            .header("Authorization", credential)
            .delete()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                throw IOException("DELETE failed: ${response.code} ${response.message}")
            }
        }
    }

    /** Simple existence / connectivity check. */
    fun testConnection(): Boolean {
        return try {
            list("/")
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Full URL for a path, for "open in browser". */
    fun urlFor(path: String): String = url(path).toString()

    private fun parsePropfind(xml: String, requestedPath: String): List<RemoteEntry> {
        val entries = mutableListOf<RemoteEntry>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        var href: String? = null
        var isDir = false
        var size = 0L
        var modified = 0L
        var contentType: String? = null
        var displayName: String? = null
        var text = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name.lowercase()) {
                        "response" -> { href = null; isDir = false; size = 0; modified = 0; contentType = null; displayName = null }
                        "collection" -> isDir = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val t = text.toString().trim()
                    when (parser.name.lowercase()) {
                        "href" -> href = t
                        "getcontentlength" -> size = t.toLongOrNull() ?: 0L
                        "getlastmodified" -> modified = parseHttpDate(t)
                        "getcontenttype" -> contentType = t.ifBlank { null }
                        "displayname" -> displayName = t.ifBlank { null }
                        "response" -> {
                            val rel = relativePath(href)
                            if (rel != null && rel != requestedPath) {
                                val name = rel.substringAfterLast('/').ifBlank { displayName ?: "" }
                                if (name.isNotBlank()) {
                                    entries.add(RemoteEntry(name, rel, isDir, size, modified, contentType))
                                }
                            }
                        }
                    }
                    text = StringBuilder()
                }
            }
            event = parser.next()
        }
        return entries.distinctBy { it.path }
    }

    /** Turns a server href (absolute URL or absolute path) into a path relative to the base URL. */
    private fun relativePath(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val rawPath = if (href.startsWith("http://") || href.startsWith("https://")) {
            href.toHttpUrl().encodedPath
        } else href
        val decoded = rawPath.split('/').filter { it.isNotEmpty() }.joinToString("/") {
            try { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { it }
        }
        val full = "/$decoded"
        val baseNorm = if (basePath == "/") "" else basePath
        if (baseNorm.isNotEmpty() && !full.startsWith("$baseNorm/") && full != baseNorm) return null
        val rel = full.removePrefix(baseNorm)
        return normalize(rel)
    }

    private fun parseHttpDate(s: String): Long {
        if (s.isBlank()) return 0L
        return try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }.parse(s)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    class AuthException(message: String) : IOException(message)

    data class RemoteEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long = 0L,
        val modifiedAt: Long = 0L,
        val contentType: String? = null
    )
}

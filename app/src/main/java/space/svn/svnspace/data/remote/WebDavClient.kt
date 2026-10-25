package space.svn.svnspace.data.remote

import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

        // Folders are always asked for with a trailing slash ("/dav/", "/dav/local/"):
        // AList and most servers answer a collection there without a redirect.
        val target = url(path, trailingSlash = true)
        val request = Request.Builder()
            .url(target)
            .header("Authorization", credential)
            .header("Depth", "1")
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val code = response.code
            if (code == 401) {
                throw AuthException("The server refused the username or password (401). Check them, and that this user is allowed to use WebDAV.")
            }
            if (!response.isSuccessful) {
                val snippet = try { response.peekBody(2048).string() } catch (_: Exception) { "" }
                throw HttpStatusException(code, explainStatus(code, response.message, target.toString(),
                    response.header("Location")?.let { response.request.url.resolve(it)?.toString() ?: it }, snippet))
            }
            val xml = response.body?.string().orEmpty()
            if (!xml.contains("multistatus", ignoreCase = true)) {
                throw NotWebDavException(
                    "The server at $target answered, but with a normal web page, not WebDAV. " +
                        "Check the path – for AList it's /dav."
                )
            }
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

    /** Like [mkcol], but also makes missing parent folders (servers answer 409 for those). */
    fun mkcols(path: String) {
        val norm = normalize(path)
        if (norm == "/") return
        try {
            mkcol(norm)
        } catch (e: IOException) {
            if (e.message?.contains("409") != true) throw e
            mkcols(norm.substringBeforeLast('/').ifEmpty { "/" })
            mkcol(norm)
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
    fun testConnection(): Boolean = probe() == null

    /**
     * Lists the top folder once. Returns null when it worked, otherwise the reason in plain
     * words (blocked http, wrong login, wrong path, timeout, certificate trouble …).
     */
    fun probe(): String? = probeDetailed().error

    /** Like [probe], also saying whether the server answered at all (for the http/https retry). */
    fun probeDetailed(): ProbeResult = try {
        list("/")
        ProbeResult(null, gotAnswer = true)
    } catch (e: Exception) {
        val answered = e is HttpStatusException || e is AuthException || e is NotWebDavException
        ProbeResult(describe(e, base), gotAnswer = answered)
    }

    data class ProbeResult(val error: String?, val gotAnswer: Boolean)

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

    /**
     * Turns a server href (absolute URL or absolute path) into a path relative to the base URL.
     * Most servers (AList included) send hrefs with the base path ("/dav/local/a.txt"); some,
     * behind a reverse proxy, leave part or all of it off ("/local/a.txt"). Both work.
     */
    private fun relativePath(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val rawPath = if (href.startsWith("http://", true) || href.startsWith("https://", true)) {
            href.toHttpUrlOrNull()?.encodedPath ?: return null
        } else href.substringBefore('?')
        val hrefSegs = rawPath.split('/').filter { it.isNotEmpty() }.map {
            try { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { it }
        }
        val baseSegs = basePath.split('/').filter { it.isNotEmpty() }
        // Longest tail of the base path that the href starts with (all of it, normally).
        for (k in 0..baseSegs.size) {
            val prefix = baseSegs.drop(k)
            if (hrefSegs.size >= prefix.size && hrefSegs.subList(0, prefix.size) == prefix) {
                return normalize(hrefSegs.drop(prefix.size).joinToString("/"))
            }
        }
        return normalize(hrefSegs.joinToString("/"))
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

    /** The server answered with an HTTP error; the message already says what it means. */
    class HttpStatusException(val code: Int, message: String) : IOException(message)

    /** The server answered 2xx, but not with a WebDAV listing (usually a web page). */
    class NotWebDavException(message: String) : IOException(message)

    /** Result of [testAddress]: the address that worked (or was tried first) and the error, if any. */
    data class TestOutcome(val url: String, val error: String?) {
        val ok get() = error == null
        val scheme get() = if (url.startsWith("https://")) "https" else "http"
    }

    companion object {
        /** A client for a saved connection: address, port and Path field combined one way everywhere. */
        fun forConnection(conn: space.svn.svnspace.data.model.Connection, password: String): WebDavClient {
            val host = conn.host ?: throw IOException("This connection has no server address. Remove it and add it again.")
            return WebDavClient(WebDavAddress.endpoint(host, conn.port, conn.basePath), conn.username ?: "", password)
        }

        /**
         * Tests what the user typed. When no http:// or https:// was typed and the first guess
         * doesn't work, the other scheme is tried too. Throws [WebDavAddress.BadAddressException]
         * for an address that can't be read at all.
         */
        fun testAddress(host: String, port: Int?, path: String?, username: String, password: String): TestOutcome {
            val urls = WebDavAddress.candidates(host, port, path)
            var first: Pair<String, ProbeResult>? = null
            for (u in urls) {
                val r = WebDavClient(u, username, password).probeDetailed()
                if (r.error == null) return TestOutcome(u, null)
                if (first == null) first = u to r
                else if (r.gotAnswer && !first.second.gotAnswer) {
                    // The second try reached a server (e.g. "wrong password"); that's the useful message.
                    return TestOutcome(u, r.error)
                }
            }
            val (u, r) = first!!
            return TestOutcome(u, r.error)
        }

        /** Plain-words reason for a failed request. */
        fun describe(e: Throwable, url: HttpUrl? = null): String {
            val where = url?.let { if (it.port == defaultPort(it.scheme)) it.host else "${it.host}:${it.port}" } ?: "the server"
            val msg = e.message.orEmpty()
            return when {
                e is AuthException || e is HttpStatusException || e is NotWebDavException -> msg
                e is java.net.UnknownServiceException && msg.contains("CLEARTEXT", true) ->
                    "Android blocked the plain http:// connection to $where."
                e is java.net.UnknownHostException ->
                    "Couldn't find a server called ${url?.host ?: "that"}. Check the address, and that your phone is on the same network."
                e is java.net.ConnectException || e is java.net.NoRouteToHostException ->
                    "Nothing answered at $where. Check the address and port, that the server is running, and that your phone is on the same network."
                e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException ->
                    "$where didn't answer in time. Check the address and port, and that your phone can reach it."
                e is javax.net.ssl.SSLHandshakeException && (e.cause is java.security.cert.CertificateException ||
                    msg.contains("Trust anchor", true) || msg.contains("certificate", true)) ->
                    "$where uses a security certificate this phone doesn't trust (self-signed?). Use http:// if the server runs plain http, or install its certificate on the phone."
                e is javax.net.ssl.SSLException ->
                    "The secure (https) connection to $where failed. If the server uses plain http, start the address with http://. ($msg)"
                e is org.xmlpull.v1.XmlPullParserException ->
                    "The server answered, but its WebDAV listing couldn't be read. ($msg)"
                msg.isNotBlank() -> msg
                else -> e.javaClass.simpleName
            }
        }

        private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

        private fun explainStatus(code: Int, reason: String, url: String, location: String?, body: String): String = when {
            code in 300..399 ->
                "The server sent $url somewhere else ($code)" + (location?.let { ". Try this address instead: $it" } ?: ".")
            code == 400 && body.contains("HTTPS port", true) ->
                "That port expects https. Start the address with https://."
            code == 400 -> "The server rejected the request (400). If it uses https, start the address with https://."
            code == 403 ->
                "Signed in, but this user isn't allowed to use WebDAV here (403). In AList, give the user WebDAV read and manage permission."
            code == 404 -> "There's no WebDAV folder at $url (404). Check the path – for AList it's /dav."
            code == 405 || code == 501 ->
                "That address doesn't take WebDAV requests ($code). Check the path – for AList it's /dav."
            code in 500..599 -> "The server had an error ($code). Check the server's own log."
            else -> "The server answered with code $code."
        }
    }

    data class RemoteEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long = 0L,
        val modifiedAt: Long = 0L,
        val contentType: String? = null
    )
}

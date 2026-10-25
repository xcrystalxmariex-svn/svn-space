package space.svn.svnspace.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns what the user typed for a WebDAV server (address, port, path) into one base URL.
 *
 * One function builds the address everywhere a [WebDavClient] is made, so the Test button,
 * saving, reconnecting, scans, the Files tab, the folder picker and the cloud browser all
 * talk to the same place:
 *
 *  - A scheme that was typed is kept (dav:// and davs:// are read as http and https).
 *  - With no scheme, local addresses (192.168.x.x, 10.x, *.local, localhost …) and custom
 *    ports such as AList's 5244 get http://; port 443 and public names get https://.
 *    The connection test also tries the other scheme when the guess doesn't connect.
 *  - The Path field is added to whatever path was typed after the host, without doubling:
 *    "http://nas:5244/dav" + "/dav" stays "http://nas:5244/dav".
 *
 * Pure Kotlin + OkHttp (no Android classes) so it can be unit tested.
 */
object WebDavAddress {

    class BadAddressException(message: String) : IllegalArgumentException(message)

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")

    fun hasExplicitScheme(host: String): Boolean = SCHEME.containsMatchIn(host.trim())

    /**
     * Scheme + host + port + any path typed in the host field (no Path field).
     * [forceScheme] replaces the guess when no scheme was typed.
     */
    fun server(host: String, port: Int? = null, forceScheme: String? = null): String {
        val raw = host.trim()
        if (raw.isEmpty()) throw BadAddressException("Enter the server address")
        if (port != null && port !in 1..65535) throw BadAddressException("The port has to be a number from 1 to 65535")
        val m = SCHEME.find(raw)
        val typedScheme = m?.groupValues?.get(1)?.lowercase()
        val rest = if (m != null) raw.substring(m.range.last + 1) else raw.removePrefix("//")
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        val pathPart = rest.substring(authority.length).takeWhile { it != '?' && it != '#' }
        val (hostName, typedPort) = splitAuthority(authority)
        if (hostName.isEmpty()) throw BadAddressException("Enter the server address, for example http://192.168.1.20:5244")
        val scheme = when (typedScheme) {
            null -> forceScheme ?: guessScheme(hostName, typedPort ?: port)
            "http", "dav", "webdav" -> "http"
            "https", "davs", "webdavs" -> "https"
            else -> throw BadAddressException("Start the address with http:// or https://")
        }
        val url = "$scheme://$authority$pathPart".toHttpUrlOrNull()
            ?: throw BadAddressException("That server address doesn't look right: $raw")
        val b = url.newBuilder().query(null).fragment(null)
        if (typedPort == null && port != null) b.port(port)
        return b.build().toString().trimEnd('/')
    }

    /** Full WebDAV base URL: [server] plus the Path field, never doubled. */
    fun endpoint(host: String, port: Int? = null, path: String? = null, forceScheme: String? = null): String {
        val base = server(host, port, forceScheme).toHttpUrl()
        val extra = pathSegments(path)
        if (extra.isEmpty()) return base.toString().trimEnd('/')
        val have = base.pathSegments.filter { it.isNotEmpty() }
        // "…/dav" + "/dav" → nothing to add; "…/remote.php" + "/remote.php/dav" → add "dav".
        var overlap = 0
        for (k in minOf(have.size, extra.size) downTo 1) {
            if (have.takeLast(k) == extra.take(k)) { overlap = k; break }
        }
        val b = base.newBuilder()
        if (base.pathSegments.lastOrNull() == "") b.removePathSegment(base.pathSegments.size - 1)
        extra.drop(overlap).forEach { b.addPathSegment(it) }
        return b.build().toString().trimEnd('/')
    }

    /** The addresses to try, best guess first; two when no scheme was typed. */
    fun candidates(host: String, port: Int? = null, path: String? = null): List<String> {
        val first = endpoint(host, port, path)
        if (hasExplicitScheme(host)) return listOf(first)
        val other = if (first.startsWith("https://")) "http" else "https"
        return listOf(first, endpoint(host, port, path, other))
    }

    /** "/dav" style form of the Path field, or null when it's empty. */
    fun normalizePath(path: String?): String? {
        val segs = pathSegments(path)
        return if (segs.isEmpty()) null else segs.joinToString("/", prefix = "/")
    }

    /** Decoded segments of a typed path ("/dav", "dav/", "/my%20files", "/my files"). */
    fun pathSegments(path: String?): List<String> {
        var p = path?.trim().orEmpty()
        if (p.isEmpty()) return emptyList()
        if (SCHEME.containsMatchIn(p)) p = p.toHttpUrlOrNull()?.encodedPath ?: return emptyList()
        p = p.takeWhile { it != '?' && it != '#' }.trim('/')
        if (p.isEmpty()) return emptyList()
        val url = "http://x/".toHttpUrl().newBuilder().addEncodedPathSegments(p).build()
        return url.pathSegments.filter { it.isNotEmpty() }
    }

    private fun splitAuthority(authority: String): Pair<String, Int?> {
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return hostPort to null
            val portText = hostPort.substring(end + 1).removePrefix(":")
            return hostPort.substring(0, end + 1) to portText.toIntOrNull()
        }
        if (hostPort.count { it == ':' } == 1) {
            val portText = hostPort.substringAfter(':')
            if (portText.isNotEmpty() && portText.toIntOrNull() == null) {
                throw BadAddressException("The port after ':' has to be a number")
            }
            return hostPort.substringBefore(':') to portText.toIntOrNull()
        }
        return hostPort to null
    }

    private fun guessScheme(hostName: String, port: Int?): String = when {
        port == 443 || port == 8443 -> "https"
        port == 80 -> "http"
        isLocal(hostName) -> "http"
        // Custom ports (AList 5244, 8080 …) are almost always plain http; the test tries https too.
        port != null -> "http"
        else -> "https"
    }

    fun isLocal(hostName: String): Boolean {
        val h = hostName.lowercase().trim('[', ']')
        if (h == "localhost" || h == "::1") return true
        if (h.startsWith("fe80:") || h.startsWith("fc") && h.contains(':') || h.startsWith("fd") && h.contains(':')) return true
        if (listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain").any { h.endsWith(it) }) return true
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        // A single name with no dots ("nas", "alist") only resolves on the local network.
        return !h.contains('.') && !h.contains(':')
    }
}

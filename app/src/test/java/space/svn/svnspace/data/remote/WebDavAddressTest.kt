package space.svn.svnspace.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavAddressTest {

    @Test fun bareLocalHostWithPortGetsHttp() {
        assertEquals("http://192.168.1.20:5244/dav", WebDavAddress.endpoint("192.168.1.20:5244", null, "/dav"))
    }

    @Test fun portFieldIsUsed() {
        assertEquals("http://192.168.1.20:5244/dav", WebDavAddress.endpoint("192.168.1.20", 5244, "dav"))
    }

    @Test fun pathInHostFieldIsKeptAndNotDoubled() {
        assertEquals("http://192.168.1.20:5244/dav", WebDavAddress.endpoint("http://192.168.1.20:5244/dav", null, null))
        assertEquals("http://192.168.1.20:5244/dav", WebDavAddress.endpoint("http://192.168.1.20:5244/dav/", 5244, "/dav/"))
        assertEquals("https://cloud.example.com/remote.php/dav",
            WebDavAddress.endpoint("https://cloud.example.com/remote.php", null, "/remote.php/dav"))
    }

    @Test fun explicitSchemeIsKept() {
        assertEquals("https://192.168.1.20:5244/dav", WebDavAddress.endpoint("https://192.168.1.20:5244", null, "/dav"))
        assertEquals("http://example.com/dav", WebDavAddress.endpoint("http://example.com", null, "/dav"))
    }

    @Test fun publicNameWithoutPortGetsHttpsAndTwoCandidates() {
        assertEquals(listOf("https://alist.example.com/dav", "http://alist.example.com/dav"),
            WebDavAddress.candidates("alist.example.com", null, "/dav"))
        assertEquals(listOf("http://alist.example.com/dav"), WebDavAddress.candidates("http://alist.example.com", null, "/dav"))
    }

    @Test fun typedPortWinsOverPortField() {
        assertEquals("http://nas.local:8080", WebDavAddress.server("nas.local:8080", 5244))
    }

    @Test fun pathWithSpaces() {
        assertEquals("http://nas:5244/dav/my%20files", WebDavAddress.endpoint("nas:5244", null, "/dav/my files"))
        assertEquals("/dav/my files", WebDavAddress.normalizePath("dav/my%20files/"))
    }
}

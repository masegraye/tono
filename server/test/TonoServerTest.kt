package org.example.kotlintest.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.example.kotlintest.rex.RexEndpoint
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TonoServerTest {
    @Test
    fun `only this machine and addresses Tailscale gives out are served`() {
        for (address in listOf("127.0.0.1", "::1", "100.64.0.1", "100.101.102.103", "100.127.255.254", "fd7a:115c:a1e0::1", "fd7a:115c:a1e0:ab12:4843:cd96:6263:1234")) {
            assertEquals(true, isLocalOrTailnet(address), address)
        }
        // The private ranges of a home or office network, the rest of 100/8, and the internet.
        for (address in listOf("192.168.1.20", "10.0.0.5", "172.16.0.9", "100.63.255.255", "100.128.0.1", "8.8.8.8", "fd7a:115c:a1e1::1", "2001:db8::1", "not an address")) {
            assertEquals(false, isLocalOrTailnet(address), address)
        }
    }

    @Test
    fun `the relay connects to this machine's server and to servers on the tailnet`() {
        val local = "unix:///run/user/1000/rex/server.sock"
        assertEquals(RexEndpoint.UnixSocket("/run/user/1000/rex/server.sock"), relayTarget("unix:///@local", local))
        assertEquals(RexEndpoint.Network("wss://studio-rex.tail1234.ts.net"), relayTarget("https://studio-rex.tail1234.ts.net", local))
        assertEquals(RexEndpoint.Network("wss://studio-rex.tail1234.ts.net"), relayTarget("wss://studio-rex.tail1234.ts.net", local))
        assertEquals(RexEndpoint.Network("ws://100.101.102.103:7297"), relayTarget("http://100.101.102.103:7297", local))
    }

    @Test
    fun `the relay connects nowhere else`() {
        val local = "unix:///run/user/1000/rex/server.sock"
        assertNull(relayTarget(null, local))
        // Another file on this machine, another machine on the local network, and the internet.
        assertNull(relayTarget("unix:///var/run/docker.sock", local))
        assertNull(relayTarget("/var/run/docker.sock", local))
        assertNull(relayTarget("http://192.168.1.1", local))
        assertNull(relayTarget("https://example.com", local))
        assertNull(relayTarget("https://ts.net.example.com", local))
        assertNull(relayTarget("http://127.0.0.1:9000", local))
        assertNull(relayTarget("ssh://studio", local))
    }

    /** A tailnet of three: this machine and a phone, both Ada's; a colleague's laptop; and a tagged build machine. */
    private val tailnet = object : TailnetDirectory {
        override suspend fun self() = TailnetDevice("desk", "ada@example.com", tagged = false)
        override suspend fun whoIs(address: String) = when (address) {
            "100.64.0.1" -> TailnetDevice("desk", "ada@example.com", tagged = false)
            "100.64.0.2" -> TailnetDevice("phone", "Ada@example.com", tagged = false)
            "100.64.0.3" -> TailnetDevice("laptop", "grace@example.com", tagged = false)
            "100.64.0.4" -> TailnetDevice("builder", "ada@example.com", tagged = true)
            else -> null
        }
    }

    private fun caller(remote: String, forwardedFor: String? = null, allowIdentified: Boolean = false) =
        runBlocking { callerOf(remote, forwardedFor, tailnet, allowIdentified) }

    @Test
    fun `this machine is let in as itself`() {
        val caller = caller("127.0.0.1")
        assertEquals(true, caller?.local)
        assertEquals("desk", caller?.device)
    }

    @Test
    fun `the owner's other devices are let in, by either way of arriving`() {
        // Through `tailscale serve`, which connects from this machine and names the device.
        val served = caller("127.0.0.1", forwardedFor = "100.64.0.2")
        assertEquals(false, served?.local)
        assertEquals("phone", served?.device)
        // Straight from the tailnet address, when the server listens on one.
        assertEquals("phone", caller("100.64.0.2")?.device)
    }

    @Test
    fun `nobody else on the tailnet is let in`() {
        assertNull(caller("127.0.0.1", forwardedFor = "100.64.0.3"), "another person")
        assertNull(caller("100.64.0.3"), "another person, directly")
        assertNull(caller("127.0.0.1", forwardedFor = "100.64.0.4"), "a tagged machine")
        assertNull(caller("127.0.0.1", forwardedFor = "100.64.0.9"), "an address the tailnet doesn't know")
        assertNull(caller("127.0.0.1", forwardedFor = "203.0.113.7"), "the internet, passed on")
        assertNull(caller("192.168.1.20"), "the local network")
        // What a device says about itself counts for nothing when it connected directly.
        assertNull(caller("100.64.0.3", forwardedFor = "100.64.0.2"))
    }

    @Test
    fun `everyone with a name can be let in, and tagged machines still are not`() {
        assertEquals("laptop", caller("127.0.0.1", forwardedFor = "100.64.0.3", allowIdentified = true)?.device)
        assertNull(caller("127.0.0.1", forwardedFor = "100.64.0.4", allowIdentified = true))
        assertNull(caller("127.0.0.1", forwardedFor = "100.64.0.9", allowIdentified = true))
    }

    @Test
    fun `a request passed on for someone else is refused, and one for the owner is told who it is`() = testApplication {
        application {
            tailnetOnly(tailnet)
            tonoAssets()
        }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/tono/whoami") { header("X-Forwarded-For", "100.64.0.3") }.status)
        val phone = client.get("/api/tono/whoami") { header("X-Forwarded-For", "100.64.0.2") }
        assertEquals(HttpStatusCode.OK, phone.status)
        assertEquals("""{"local":false,"device":"phone","login":"Ada@example.com"}""", phone.bodyAsText())
        assertEquals("""{"local":true,"device":"desk","login":"ada@example.com"}""", client.get("/api/tono/whoami").bodyAsText())
    }

    @Test
    fun `a file is sent compressed, and not sent again to a browser that has it`() = testApplication {
        val directory = createTempDirectory("tono-web").toFile()
        File(directory, "app.js").writeText("const repeated = 1;\n".repeat(400))
        application {
            sentSmall()
            webApp(directory)
        }
        val first = createClient { }.get("/app.js") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals("gzip", first.headers[HttpHeaders.ContentEncoding])
        assertEquals("no-cache", first.headers[HttpHeaders.CacheControl])
        val modified = assertNotNull(first.headers[HttpHeaders.LastModified])
        val again = client.get("/app.js") { header(HttpHeaders.IfModifiedSince, modified) }
        assertEquals(HttpStatusCode.NotModified, again.status)
        directory.deleteRecursively()
    }
}


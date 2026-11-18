package org.example.kotlintest.server

import org.example.kotlintest.rex.RexEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
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
}

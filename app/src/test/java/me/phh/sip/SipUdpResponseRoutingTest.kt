//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SipUdpResponseRoutingTest {
    private val packetSource = InetAddress.getByName("2001:db8::20")

    @Test
    fun `bare rport routes to packet source and is completed`() {
        val route = route("SIP/2.0/UDP [2001:db8::10]:5067;branch=z9;rport")

        assertSame(packetSource, route.destinationAddress)
        assertNull(route.destinationHost)
        assertEquals(32922, route.destinationPort)
        val text = route.bytes.toString(Charsets.US_ASCII)
        assertTrue(text.contains(";rport=32922"))
        assertTrue(text.contains(";received=2001:db8:0:0:0:0:0:20"))
    }

    @Test
    fun `explicit rport uses packet address and advertised port`() {
        val route = route("SIP/2.0/UDP proxy.example:5067;branch=z9;rport=32920")

        assertSame(packetSource, route.destinationAddress)
        assertEquals(32920, route.destinationPort)
    }

    @Test
    fun `missing rport follows Via sent-by instead of packet source`() {
        val route = route("SIP/2.0/UDP [2001:db8::30]:5067;branch=z9")

        assertNull(route.destinationAddress)
        assertEquals("2001:db8::30", route.destinationHost)
        assertEquals(5067, route.destinationPort)
    }

    @Test
    fun `received parameter overrides Via host but retains sent-by port`() {
        val route = route(
            "SIP/2.0/UDP proxy.example:5070;branch=z9;received=2001:db8::40",
        )

        assertEquals("2001:db8::40", route.destinationHost)
        assertEquals(5070, route.destinationPort)
    }

    @Test
    fun `response without Via safely falls back to packet source`() {
        val bytes = response(null)
        val route = SipUdpResponseRouting.route(bytes, packetSource, 32922)

        assertSame(packetSource, route.destinationAddress)
        assertEquals(32922, route.destinationPort)
        assertTrue(bytes.contentEquals(route.bytes))
    }

    @Test
    fun `compact Via header is routed`() {
        val bytes = (
            "SIP/2.0 200 OK\r\n" +
                "v: SIP/2.0/UDP proxy.example:5080;branch=z9\r\n" +
                "Content-Length: 0\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val route = SipUdpResponseRouting.route(bytes, packetSource, 32922)

        assertEquals("proxy.example", route.destinationHost)
        assertEquals(5080, route.destinationPort)
    }

    private fun route(via: String): SipUdpResponseRoute =
        SipUdpResponseRouting.route(response(via), packetSource, 32922)

    private fun response(via: String?): ByteArray {
        val viaHeader = via?.let { "Via: $it\r\n" }.orEmpty()
        return (
            "SIP/2.0 200 OK\r\n" +
                viaHeader +
                "Call-ID: test\r\n" +
                "Content-Length: 0\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
    }
}

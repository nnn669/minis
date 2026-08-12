package com.openminis.app.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class Ipv4PreferredDnsTest {

    private fun v4(ip: String): Inet4Address = InetAddress.getByName(ip) as Inet4Address

    private fun v6(ip: String): Inet6Address = InetAddress.getByName(ip) as Inet6Address

    @Test
    fun ipv4AddressesAreMovedAheadOfIpv6() {
        val v6a = v6("2606:4700:10::ac42:9f98")
        val v4a = v4("172.66.159.152")
        val v6b = v6("2606:4700:10::6814:27f9")
        assertEquals(
            listOf(v4a, v6a, v6b),
            Ipv4PreferredDns.prioritizeIpv4(listOf(v6a, v4a, v6b)),
        )
    }

    @Test
    fun relativeOrderIsPreservedWithinEachFamily() {
        val v4a = v4("172.66.159.152")
        val v4b = v4("104.16.39.249")
        val v6a = v6("2606:4700:10::ac42:9f98")
        val v6b = v6("2606:4700:10::6814:27f9")
        assertEquals(
            listOf(v4a, v4b, v6a, v6b),
            Ipv4PreferredDns.prioritizeIpv4(listOf(v6a, v6b, v4a, v4b)),
        )
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals(emptyList<InetAddress>(), Ipv4PreferredDns.prioritizeIpv4(emptyList()))
    }

    @Test
    fun ipv4OnlyInputIsUnchanged() {
        val a = v4("172.66.159.152")
        val b = v4("1.1.1.1")
        assertEquals(listOf(a, b), Ipv4PreferredDns.prioritizeIpv4(listOf(a, b)))
    }
}

package com.openminis.app.network

import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.Dns

/**
 * [T-android-ipv6-connect-hang] DNS resolver that prefers IPv4 addresses.
 *
 * Why: on networks with broken / half-broken IPv6 (SYN dropped, or the TCP
 * handshake completes but TLS packets are black-holed), getaddrinfo still
 * returns IPv6 addresses FIRST for dual-stack hosts. OkHttp dials addresses
 * in the order the resolver returns them, so a request can bind to the dead
 * IPv6 path and stall until the connect / TTFB watchdog gives up — and every
 * retry hits the same address order. Observed against api.ciyuan.fast in
 * minis-2026-08-12.log: three attempts × 30s, all stuck on the IPv6 address,
 * never falling back to the healthy IPv4 address returned in the same DNS
 * response.
 *
 * Reordering the lookup so IPv4 is tried first lets the working path win
 * immediately. IPv6 addresses stay in the list as a fallback, so dual-stack
 * and IPv6-only networks keep working.
 */
object Ipv4PreferredDns : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = try {
            Dns.SYSTEM.lookup(hostname)
        } catch (t: Throwable) {
            emptyList()
        }
        return prioritizeIpv4(addresses)
    }

    /**
     * Pure reorder helper — separate from [lookup] so the ordering logic is
     * unit-testable without touching the network. Stable sort: IPv4 addresses
     * move to the front, IPv6 addresses follow in their original relative
     * order.
     */
    internal fun prioritizeIpv4(addresses: List<InetAddress>): List<InetAddress> =
        addresses.sortedWith(compareBy<InetAddress> { it !is Inet4Address })
}

package com.zaneschepke.wireguardautotunnel.xt

import java.net.InetAddress

/** Small helpers for IP literals and WireGuard endpoint strings. Never performs DNS. */
object XtIp {

    private val IPV4 =
        Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val IPV6_CHARS = Regex("^[0-9a-fA-F:.]+$")
    private val IPV4_IN_TEXT =
        Regex("(?<![\\d.])(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}(?![\\d.])")
    private val IPV6_IN_TEXT = Regex("(?<![0-9a-fA-F:])[0-9a-fA-F]{0,4}(:[0-9a-fA-F]{0,4}){2,7}(?![0-9a-fA-F:])")

    fun stripBrackets(host: String): String = host.trim().removePrefix("[").removeSuffix("]")

    fun isIpv4(value: String): Boolean = IPV4.matches(stripBrackets(value))

    fun isIpv6(value: String): Boolean {
        val v = stripBrackets(value).substringBefore('%')
        if (!v.contains(':') || !IPV6_CHARS.matches(v)) return false
        // The string only contains hex digits, ':' and '.', so this parses a literal and never
        // triggers a DNS lookup.
        return try {
            InetAddress.getByName(v) != null
        } catch (e: Exception) {
            false
        }
    }

    fun isIpLiteral(value: String?): Boolean =
        !value.isNullOrBlank() && (isIpv4(value) || isIpv6(value))

    /** Canonical form used for comparisons: no brackets, lower case, compressed IPv6. */
    fun normalize(value: String): String {
        val v = stripBrackets(value).substringBefore('%').lowercase()
        if (isIpv4(v)) return v
        if (!isIpv6(v)) return v
        return try {
            InetAddress.getByName(v).hostAddress?.substringBefore('%')?.lowercase() ?: v
        } catch (e: Exception) {
            v
        }
    }

    fun formatEndpoint(ip: String, port: Int): String {
        val clean = stripBrackets(ip)
        return if (clean.contains(':')) "[$clean]:$port" else "$clean:$port"
    }

    /** Pulls every IP literal out of a text body (e.g. a "what is my IP" page). */
    fun extractIps(text: String): List<String> {
        val v4 = IPV4_IN_TEXT.findAll(text).map { it.value }
        val v6 = IPV6_IN_TEXT.findAll(text).map { it.value }.filter { isIpv6(it) }
        return (v4 + v6).map { normalize(it) }.distinct().toList()
    }

    /** Picks the best new address, keeping the IP family the tunnel already uses when possible. */
    fun pickPreferred(candidates: List<String>, current: Collection<String>): String? {
        if (candidates.isEmpty()) return null
        val wantV6 = current.isNotEmpty() && current.all { it.contains(':') }
        return candidates.firstOrNull { it.contains(':') == wantV6 } ?: candidates.first()
    }
}

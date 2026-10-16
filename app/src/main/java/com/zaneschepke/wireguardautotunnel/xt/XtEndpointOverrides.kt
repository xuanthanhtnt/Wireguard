package com.zaneschepke.wireguardautotunnel.xt

import com.wgtunnel.parser.Config
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived endpoint pins used after the watchdog has detected that a server's IP changed.
 *
 * The phone's own DNS cache can keep returning the old address for up to the record's TTL. While a
 * pin is active, the next tunnel start uses the freshly detected IP directly, so the tunnel comes
 * back immediately instead of waiting for the cache to expire. Pins expire on their own, after which
 * normal DNS resolution of the configured hostname is used again.
 */
object XtEndpointOverrides {

    private data class Entry(val endpoints: Map<String, String>, val expiresAtMs: Long)

    private val pins = ConcurrentHashMap<Int, Entry>()

    const val DEFAULT_TTL_MS = 15 * 60 * 1000L

    /** @param endpoints peer public key -> "ip:port" endpoint */
    fun put(tunnelId: Int, endpoints: Map<String, String>, ttlMs: Long = DEFAULT_TTL_MS) {
        if (endpoints.isEmpty()) return
        val merged = (pins[tunnelId]?.takeIf { it.expiresAtMs > now() }?.endpoints ?: emptyMap()) + endpoints
        pins[tunnelId] = Entry(merged, now() + ttlMs)
    }

    fun clear(tunnelId: Int) {
        pins.remove(tunnelId)
    }

    fun active(tunnelId: Int): Map<String, String> {
        val entry = pins[tunnelId] ?: return emptyMap()
        if (entry.expiresAtMs <= now()) {
            pins.remove(tunnelId)
            return emptyMap()
        }
        return entry.endpoints
    }

    /** Returns [config] with pinned peer endpoints applied, or [config] unchanged. */
    fun apply(tunnelId: Int, config: Config): Config {
        val endpoints = active(tunnelId)
        if (endpoints.isEmpty()) return config
        if (config.peers.none { endpoints.containsKey(it.publicKey) }) return config
        return config.copy(
            peers =
                config.peers.map { peer ->
                    endpoints[peer.publicKey]?.let { peer.copy(endpoint = it) } ?: peer
                }
        )
    }

    private fun now() = System.currentTimeMillis()
}

/** Lets startup / always-on code know the watchdog intentionally took the VPN down. */
object XtPauseGate {
    @Volatile var paused: Boolean = false
}

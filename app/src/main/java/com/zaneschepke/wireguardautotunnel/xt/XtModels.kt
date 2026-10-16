package com.zaneschepke.wireguardautotunnel.xt

import org.json.JSONArray
import org.json.JSONObject

/** How the watchdog checks whether a paused server has come back. */
enum class ProbeMethod {
    /** Briefly bring the tunnel up and wait for a WireGuard handshake. Most reliable. */
    HANDSHAKE,

    /** ICMP ping (or TCP connect when a port is set) to the server; no internet interruption. */
    PING;

    companion object {
        fun fromName(name: String?): ProbeMethod = entries.firstOrNull { it.name == name } ?: HANDSHAKE
    }
}

/** WireguardXT settings, stored as one JSON document in DataStore. */
data class XtSettings(
    val ipWatchEnabled: Boolean = true,
    val ipWatchIntervalSec: Int = DEFAULT_IP_WATCH_INTERVAL_SEC,
    val useDoh: Boolean = true,
    val autoPauseEnabled: Boolean = true,
    val pauseAfterSec: Int = DEFAULT_PAUSE_AFTER_SEC,
    val probeIntervalSec: Int = DEFAULT_PROBE_INTERVAL_SEC,
    val probeMethod: ProbeMethod = ProbeMethod.HANDSHAKE,
    val probeTcpPort: Int = 0,
    val trialTimeoutSec: Int = DEFAULT_TRIAL_TIMEOUT_SEC,
    val suspendKillSwitch: Boolean = true,
    val notifyEvents: Boolean = true,
    /** tunnelId -> DDNS hostname or http(s) URL returning the server IP. */
    val ipSources: Map<Int, String> = emptyMap(),
) {

    fun toJson(): String {
        val sources = JSONObject()
        ipSources.forEach { (id, value) -> sources.put(id.toString(), value) }
        return JSONObject()
            .put("ipWatchEnabled", ipWatchEnabled)
            .put("ipWatchIntervalSec", ipWatchIntervalSec)
            .put("useDoh", useDoh)
            .put("autoPauseEnabled", autoPauseEnabled)
            .put("pauseAfterSec", pauseAfterSec)
            .put("probeIntervalSec", probeIntervalSec)
            .put("probeMethod", probeMethod.name)
            .put("probeTcpPort", probeTcpPort)
            .put("trialTimeoutSec", trialTimeoutSec)
            .put("suspendKillSwitch", suspendKillSwitch)
            .put("notifyEvents", notifyEvents)
            .put("ipSources", sources)
            .toString()
    }

    companion object {
        const val DEFAULT_IP_WATCH_INTERVAL_SEC = 120
        const val DEFAULT_PAUSE_AFTER_SEC = 90
        const val DEFAULT_PROBE_INTERVAL_SEC = 180
        const val DEFAULT_TRIAL_TIMEOUT_SEC = 20

        val IP_WATCH_INTERVAL_OPTIONS = listOf(60, 120, 300, 600, 900, 1800)
        val PAUSE_AFTER_OPTIONS = listOf(45, 60, 90, 120, 180, 300)
        val PROBE_INTERVAL_OPTIONS = listOf(60, 120, 180, 300, 600, 900)
        val TRIAL_TIMEOUT_OPTIONS = listOf(10, 15, 20, 30, 45)

        fun fromJson(json: String?): XtSettings {
            if (json.isNullOrBlank()) return XtSettings()
            return try {
                val o = JSONObject(json)
                val defaults = XtSettings()
                val sources = mutableMapOf<Int, String>()
                o.optJSONObject("ipSources")?.let { src ->
                    val keys = src.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val id = key.toIntOrNull() ?: continue
                        val value = src.optString(key, "").trim()
                        if (value.isNotEmpty()) sources[id] = value
                    }
                }
                XtSettings(
                    ipWatchEnabled = o.optBoolean("ipWatchEnabled", defaults.ipWatchEnabled),
                    ipWatchIntervalSec =
                        o.optInt("ipWatchIntervalSec", defaults.ipWatchIntervalSec).coerceIn(30, 86_400),
                    useDoh = o.optBoolean("useDoh", defaults.useDoh),
                    autoPauseEnabled = o.optBoolean("autoPauseEnabled", defaults.autoPauseEnabled),
                    pauseAfterSec =
                        o.optInt("pauseAfterSec", defaults.pauseAfterSec).coerceIn(20, 3_600),
                    probeIntervalSec =
                        o.optInt("probeIntervalSec", defaults.probeIntervalSec).coerceIn(30, 86_400),
                    probeMethod = ProbeMethod.fromName(o.optString("probeMethod", "")),
                    probeTcpPort = o.optInt("probeTcpPort", 0).coerceIn(0, 65_535),
                    trialTimeoutSec =
                        o.optInt("trialTimeoutSec", defaults.trialTimeoutSec).coerceIn(5, 120),
                    suspendKillSwitch = o.optBoolean("suspendKillSwitch", defaults.suspendKillSwitch),
                    notifyEvents = o.optBoolean("notifyEvents", defaults.notifyEvents),
                    ipSources = sources,
                )
            } catch (e: Exception) {
                XtSettings()
            }
        }
    }
}

/** A tunnel the watchdog has taken down because its server stopped answering. Persisted. */
data class PauseState(
    val tunnelId: Int,
    val tunnelName: String,
    val pausedAtMs: Long,
    val killSwitchSuspended: Boolean,
    /** peer public key -> server IPs that were in use when the tunnel was paused. */
    val knownIps: Map<String, List<String>> = emptyMap(),
    val probeCount: Int = 0,
    val lastProbeAtMs: Long = 0L,
) {
    fun toJson(): String {
        val known = JSONObject()
        knownIps.forEach { (key, ips) -> known.put(key, JSONArray(ips)) }
        return JSONObject()
            .put("tunnelId", tunnelId)
            .put("tunnelName", tunnelName)
            .put("pausedAtMs", pausedAtMs)
            .put("killSwitchSuspended", killSwitchSuspended)
            .put("knownIps", known)
            .put("probeCount", probeCount)
            .put("lastProbeAtMs", lastProbeAtMs)
            .toString()
    }

    companion object {
        fun fromJson(json: String?): PauseState? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val known = mutableMapOf<String, List<String>>()
                o.optJSONObject("knownIps")?.let { k ->
                    val keys = k.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val arr = k.optJSONArray(key) ?: continue
                        known[key] = (0 until arr.length()).map { arr.optString(it) }
                    }
                }
                PauseState(
                    tunnelId = o.getInt("tunnelId"),
                    tunnelName = o.optString("tunnelName", ""),
                    pausedAtMs = o.optLong("pausedAtMs", 0L),
                    killSwitchSuspended = o.optBoolean("killSwitchSuspended", false),
                    knownIps = known,
                    probeCount = o.optInt("probeCount", 0),
                    lastProbeAtMs = o.optLong("lastProbeAtMs", 0L),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

enum class XtEventType {
    IP_CHANGED,
    IP_UPDATED,
    IP_CHECK_FAILED,
    PAUSED,
    RESUMED,
    PAUSE_CANCELLED,
    INFO;

    companion object {
        fun fromName(name: String?): XtEventType = entries.firstOrNull { it.name == name } ?: INFO
    }
}

/** One line of the watchdog history shown in the app. Message is already localized. */
data class XtEvent(val atMs: Long, val type: XtEventType, val message: String) {
    companion object {
        const val MAX_EVENTS = 40

        fun listToJson(events: List<XtEvent>): String {
            val arr = JSONArray()
            events.forEach { e ->
                arr.put(
                    JSONObject().put("at", e.atMs).put("type", e.type.name).put("msg", e.message)
                )
            }
            return arr.toString()
        }

        fun listFromJson(json: String?): List<XtEvent> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    XtEvent(
                        atMs = o.optLong("at", 0L),
                        type = XtEventType.fromName(o.optString("type", "")),
                        message = o.optString("msg", ""),
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/** Result of looking up the server address for one peer. */
data class EndpointStatus(
    val tunnelId: Int,
    val tunnelName: String,
    val peerKey: String,
    /** Host part of the configured endpoint (hostname or IP literal). */
    val endpointHost: String,
    /** What was looked up: endpoint hostname, configured DDNS name or URL. Null = nothing to watch. */
    val source: String?,
    val currentIps: List<String>,
    val resolvedIps: List<String>,
    val checkedAtMs: Long,
    val error: String? = null,
    /**
     * Set when the tunnel cannot reach its server and public DNS-over-HTTPS points elsewhere while
     * the local DNS cache still returns the old address.
     */
    val staleLocalDns: Boolean = false,
) {
    val changed: Boolean
        get() =
            source != null &&
                currentIps.isNotEmpty() &&
                resolvedIps.isNotEmpty() &&
                (staleLocalDns || currentIps.none { it in resolvedIps })
}

/** Live state of the watchdog, for the UI and notifications. Not persisted. */
data class WatchdogRuntime(
    val pause: PauseState? = null,
    val nextProbeAtMs: Long? = null,
    val lastIpCheckAtMs: Long? = null,
    val checking: Boolean = false,
    val endpoints: List<EndpointStatus> = emptyList(),
    val failingSinceMs: Long? = null,
    val failingTunnelName: String? = null,
)

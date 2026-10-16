package com.zaneschepke.wireguardautotunnel.xt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import com.zaneschepke.wireguardautotunnel.util.network.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Network helpers that deliberately go around the tunnel.
 *
 * While WireguardXT's own VPN is up, every request from the app would normally be routed into the
 * (possibly dead) tunnel. The VPN owner app is allowed to bind sockets to the physical network, so
 * all lookups and checks here are bound to the underlying Wi-Fi / mobile / ethernet network.
 */
class XtNetwork(private val context: Context, private val networkUtils: NetworkUtils) :
    XtNetworkApi {

    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    /** Best non-VPN network with internet, validated ones first. */
    @Suppress("DEPRECATION")
    fun underlyingNetwork(): Network? {
        val cm = connectivityManager ?: return null
        val candidates =
            cm.allNetworks.mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return@mapNotNull null
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                network to caps
            }
        if (candidates.isEmpty()) return null
        return candidates
            .sortedWith(
                compareByDescending<Pair<Network, NetworkCapabilities>> {
                        it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    }
                    .thenByDescending {
                        it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            it.second.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    }
            )
            .first()
            .first
    }

    /** True when a physical network that Android validated for internet access exists. */
    override fun hasValidatedUnderlay(): Boolean {
        val cm = connectivityManager ?: return false
        val network = underlyingNetwork() ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Looks up [source]: a hostname (system DNS + optional DoH) or an http(s) URL returning an IP.
     *
     * DoH is skipped when the system answer already confirms an address in [current] and the tunnel
     * is not failing ([forceDoh] false): it could not change the outcome, so we save the requests.
     */
    override suspend fun lookup(
        source: String,
        useDoh: Boolean,
        current: Collection<String>,
        forceDoh: Boolean,
    ): XtLookupResult {
        val trimmed = source.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            return try {
                val ips = fetchIpsFromUrl(trimmed)
                if (ips.isEmpty()) XtLookupResult(error = "no IP in response") else XtLookupResult(system = ips)
            } catch (e: Exception) {
                XtLookupResult(error = e.message ?: e.javaClass.simpleName)
            }
        }
        if (XtIp.isIpLiteral(trimmed)) return XtLookupResult(system = listOf(XtIp.normalize(trimmed)))

        val host = XtIp.stripBrackets(trimmed)
        var error: String? = null
        val system =
            try {
                resolveSystem(host)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                emptyList()
            }
        val systemConfirmsCurrent = system.isNotEmpty() && system.any { it in current }
        val doh =
            if (useDoh && (forceDoh || !systemConfirmsCurrent)) {
                try {
                    resolveDoh(host)
                } catch (e: Exception) {
                    if (error == null) error = e.message ?: e.javaClass.simpleName
                    emptyList()
                }
            } else emptyList()
        return XtLookupResult(system = system, doh = doh, error = if (system.isEmpty() && doh.isEmpty()) error else null)
    }

    /** Resolves with the physical network's DNS servers, bypassing the tunnel. */
    suspend fun resolveSystem(host: String): List<String> =
        withContext(Dispatchers.IO) {
            val network = underlyingNetwork()
            val addresses: Array<InetAddress> =
                network?.getAllByName(host) ?: InetAddress.getAllByName(host)
            addresses.mapNotNull { it.hostAddress }.map { XtIp.normalize(it) }.distinct()
        }

    /** DNS-over-HTTPS lookup (Cloudflare, then Google), bound to the physical network. */
    suspend fun resolveDoh(host: String): List<String> =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (resolver in DOH_RESOLVERS) {
                try {
                    val v4 = dohQuery(resolver, host, "A")
                    val v6 =
                        try {
                            dohQuery(resolver, host, "AAAA")
                        } catch (e: Exception) {
                            emptyList()
                        }
                    return@withContext (v4 + v6).distinct()
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("DoH lookup failed")
        }

    private fun dohQuery(baseUrl: String, host: String, type: String): List<String> {
        val url = URL("$baseUrl?name=${URLEncoder.encode(host, "UTF-8")}&type=$type")
        val body = httpGet(url, accept = "application/dns-json")
        val json = JSONObject(body)
        val answers = json.optJSONArray("Answer") ?: return emptyList()
        val wantedType = if (type == "A") 1 else 28
        val result = mutableListOf<String>()
        for (i in 0 until answers.length()) {
            val answer = answers.optJSONObject(i) ?: continue
            if (answer.optInt("type") != wantedType) continue
            val data = answer.optString("data", "")
            if (XtIp.isIpLiteral(data)) result += XtIp.normalize(data)
        }
        return result
    }

    private suspend fun fetchIpsFromUrl(url: String): List<String> =
        withContext(Dispatchers.IO) { XtIp.extractIps(httpGet(URL(url), accept = "text/plain")) }

    /** Checks plain internet access over the physical network (Android/Cloudflare 204 endpoints). */
    override suspend fun internetReachable(): Boolean =
        withContext(Dispatchers.IO) {
            for (endpoint in CONNECTIVITY_CHECKS) {
                try {
                    val connection = openConnection(URL(endpoint))
                    // A captive portal answers with a redirect or a login page, never with 204
                    connection.instanceFollowRedirects = false
                    try {
                        if (connection.responseCode == 204) return@withContext true
                    } finally {
                        connection.disconnect()
                    }
                } catch (e: Exception) {
                    Timber.d("XT connectivity check $endpoint failed: ${e.message}")
                }
            }
            false
        }

    /** TCP connect to [ip]:[port] over the physical network. */
    override suspend fun tcpReachable(ip: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val network = underlyingNetwork()
                val socket: Socket = network?.socketFactory?.createSocket() ?: Socket()
                socket.use {
                    it.connect(InetSocketAddress(XtIp.stripBrackets(ip), port), TCP_TIMEOUT_MS)
                    true
                }
            } catch (e: Exception) {
                false
            }
        }

    /** ICMP ping; used while the tunnel is paused, so the default network is the physical one. */
    override suspend fun ping(ip: String): Boolean =
        try {
            networkUtils.pingWithStats(XtIp.stripBrackets(ip), count = 3, timeoutMillis = 6_000L).isReachable
        } catch (e: TimeoutCancellationException) {
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    private fun openConnection(url: URL): HttpURLConnection {
        val network = underlyingNetwork()
        val connection =
            (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.useCaches = false
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "WireguardXT")
        return connection
    }

    private fun httpGet(url: URL, accept: String): String {
        val connection = openConnection(url)
        connection.setRequestProperty("Accept", accept)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return connection.inputStream.use { it.readLimited(MAX_BODY_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readLimited(limit: Int): String {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = read(buffer, total, limit - total)
            if (read <= 0) break
            total += read
        }
        return String(buffer, 0, total, Charsets.UTF_8)
    }

    companion object {
        private const val TIMEOUT_MS = 5_000
        private const val TCP_TIMEOUT_MS = 4_000
        private const val MAX_BODY_BYTES = 16 * 1024

        private val DOH_RESOLVERS =
            listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve")

        private val CONNECTIVITY_CHECKS =
            listOf(
                "https://connectivitycheck.gstatic.com/generate_204",
                "https://cp.cloudflare.com/generate_204",
                "https://www.google.com/generate_204",
            )
    }
}

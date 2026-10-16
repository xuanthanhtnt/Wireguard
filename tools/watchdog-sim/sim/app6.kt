package com.zaneschepke.wireguardautotunnel.util.network
class NetworkUtils {
    suspend fun pingWithStats(host: String, count: Int, timeoutMillis: Long = (count * 2000L)): PingStats = PingStats()
}

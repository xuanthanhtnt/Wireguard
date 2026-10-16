package com.wgtunnel.parser
// Sim version: real parseEndpoint, tiny "key|endpoint;key|endpoint" quick format
data class PeerSection(val publicKey: String, val allowedIPs: String? = null, val endpoint: String? = null, val presharedKey: String? = null, val persistentKeepalive: String? = null, val comments: List<String> = emptyList()) {
    val host: String? get() = endpoint?.let { Config.parseEndpoint(it) }?.first
    val port: Int? get() = endpoint?.let { Config.parseEndpoint(it) }?.second?.toIntOrNull()
}
class InterfaceSection
data class Config(val `interface`: InterfaceSection, val peers: List<PeerSection> = emptyList(), val name: String? = null, val headerComments: List<String> = emptyList()) {
    fun asQuickString(): String = peers.joinToString(";") { "${it.publicKey}|${it.endpoint}" }
    companion object {
        fun parseEndpoint(endpoint: String): Pair<String?, String?> {
            var host: String; var portStr: String?
            if (endpoint.startsWith("[")) {
                val endBracket = endpoint.lastIndexOf("]")
                if (endBracket == -1 || !endpoint.substring(endBracket + 1).startsWith(":")) return null to null
                host = endpoint.take(endBracket + 1); portStr = endpoint.substring(endBracket + 2)
            } else {
                val parts = endpoint.split(":", limit = 2)
                if (parts.size != 2) return null to null
                host = parts[0]; portStr = parts[1]
            }
            return host to portStr
        }
        fun parseQuickString(configString: String): Config = Config(InterfaceSection(), configString.split(";").filter { it.isNotBlank() }.map { val (k, e) = it.split("|"); PeerSection(k, endpoint = e) })
    }
}
data class ActivePeer(val publicKey: String, val allowedIPs: String? = null, val endpoint: String? = null) {
    val host: String? get() = endpoint?.let { Config.parseEndpoint(it) }?.first
}
data class ActiveConfig(val interfaceSection: InterfaceSection, val peers: List<ActivePeer>)

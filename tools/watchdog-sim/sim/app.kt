package com.zaneschepke.wireguardautotunnel.domain.model
import com.wgtunnel.parser.Config
data class TunnelConfig(val id: Int = 0, val name: String, val quickConfig: String = "") {
    fun getConfig(): Config = Config.parseQuickString(quickConfig)
    val isGlobalConfig: Boolean get() = name == "4675ab06-903a-438b-8485-6ea4187a9512"
}
data class LockdownSettings(val id: Int = 0)
data class GeneralSettings(val tunnelMode: com.zaneschepke.wireguardautotunnel.domain.enums.TunnelMode = com.zaneschepke.wireguardautotunnel.domain.enums.TunnelMode.VPN)

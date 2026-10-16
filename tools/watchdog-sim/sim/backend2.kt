package com.wgtunnel.backend.model
import com.wgtunnel.parser.Config
sealed class BackendMode {
    abstract val config: Config
    sealed class Proxy : BackendMode() {
        data class Standard(override val config: Config) : Proxy()
        data class KillSwitchPrimary(override val config: Config) : Proxy()
    }
    data class Vpn(override val config: Config) : BackendMode()
}

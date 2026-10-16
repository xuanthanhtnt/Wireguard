package com.wgtunnel.backend.state
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.model.BackendMode
import com.wgtunnel.backend.model.dns.BootstrapResolution
import com.wgtunnel.parser.ActiveConfig
data class ActiveTunnel(
    val tunnel: Tunnel? = null,
    val mode: BackendMode? = null,
    val transportState: Tunnel.State = Tunnel.State.Down,
    val activeConfig: ActiveConfig? = null,
    val lastBootstrapResolution: BootstrapResolution? = null,
) {
    fun isTransportHealthy(): Boolean = transportState is Tunnel.State.Up.Healthy
    fun isHandshakeFailure(): Boolean = transportState is Tunnel.State.Up.HandshakeFailure
}
data class BackendStatus(val activeTunnels: Map<Int, ActiveTunnel> = emptyMap())

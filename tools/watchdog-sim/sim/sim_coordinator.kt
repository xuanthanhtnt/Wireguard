package com.zaneschepke.wireguardautotunnel.core.orchestration
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.model.BackendMode
import com.wgtunnel.backend.state.ActiveTunnel
import com.wgtunnel.parser.*
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelActionSource
import com.zaneschepke.wireguardautotunnel.domain.events.TunnelActionEvent
import com.zaneschepke.wireguardautotunnel.domain.model.LockdownSettings
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.xt.XtEndpointOverrides
import com.zaneschepke.wireguardautotunnel.xt.XtIp
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Simulated world: DNS, the server, the phone's own internet. */
object World {
    val systemDns = mutableMapOf<String, List<String>>()
    val publicDns = mutableMapOf<String, List<String>>()
    @Volatile var serverIp = "203.0.113.10"
    @Volatile var serverUp = true
    @Volatile var internet = true
    @Volatile var pingable = true
    @Volatile var lockdownMode = false
    val status = MutableStateFlow(com.wgtunnel.backend.state.BackendStatus())
    @Volatile var killSwitch = false
    val log = mutableListOf<String>()
    fun resolve(host: String): String? = if (XtIp.isIpLiteral(host)) XtIp.stripBrackets(host) else systemDns[host]?.firstOrNull()
    fun handshakeOk(ip: String?) = serverUp && ip != null && XtIp.normalize(ip) == XtIp.normalize(serverIp)
    fun reset() { systemDns.clear(); publicDns.clear(); serverIp = "203.0.113.10"; serverUp = true; internet = true; pingable = true; lockdownMode = false; status.value = com.wgtunnel.backend.state.BackendStatus(); killSwitch = false; log.clear() }
    /** Re-evaluates handshakes, like the native backend does continuously. */
    fun tick() {
        status.update { st -> st.copy(activeTunnels = st.activeTunnels.mapValues { (_, at) ->
            val ip = at.activeConfig?.peers?.firstOrNull()?.host
            at.copy(transportState = if (handshakeOk(ip)) Tunnel.State.Up.Healthy else Tunnel.State.Up.HandshakeFailure)
        }) }
    }
    fun bringUp(id: Int, name: String, config: Config) {
        val peers = config.peers.map { p -> ActivePeer(p.publicKey, endpoint = resolve(p.host!!)?.let { XtIp.formatEndpoint(it, p.port!!) }) }
        val tunnel = object : Tunnel { override val id = id; override val name = name }
        val at = ActiveTunnel(tunnel = tunnel, mode = if (lockdownMode) BackendMode.Proxy.KillSwitchPrimary(config) else BackendMode.Vpn(config), transportState = Tunnel.State.Starting, activeConfig = ActiveConfig(InterfaceSection(), peers))
        status.update { it.copy(activeTunnels = it.activeTunnels + (id to at)) }
        if (lockdownMode) killSwitch = true
        log += "UP $name -> ${peers.map { it.endpoint }}"
    }
    fun bringDown(id: Int) { if (status.value.activeTunnels.containsKey(id)) log += "DOWN $id"; status.update { it.copy(activeTunnels = it.activeTunnels - id) } }
}

class TunnelCoordinator {
    private val _actions = MutableSharedFlow<TunnelActionEvent>(extraBufferCapacity = 8)
    val actions = _actions.asSharedFlow()
    private val mutex = Mutex()
    suspend fun awaitReady() {}
    suspend fun startTunnel(config: TunnelConfig, source: TunnelActionSource = TunnelActionSource.USER) = mutex.withLock { startInternal(config, source) }
    suspend fun stopTunnel(id: Int, source: TunnelActionSource = TunnelActionSource.USER) = mutex.withLock { stopInternal(id, source) }
    inner class Exclusive internal constructor() {
        suspend fun start(config: TunnelConfig, source: TunnelActionSource) = startInternal(config, source)
        suspend fun stop(id: Int, source: TunnelActionSource) = stopInternal(id, source)
    }
    private val exclusive = Exclusive()
    suspend fun <T> exclusively(block: suspend (Exclusive) -> T): T = mutex.withLock { block(exclusive) }
    suspend fun applyLockdownSettings(settings: LockdownSettings) = mutex.withLock { World.killSwitch = true; World.log += "KS ON" }
    private suspend fun startInternal(config: TunnelConfig, source: TunnelActionSource) {
        World.status.value.activeTunnels.keys.toList().forEach { stopInternal(it, source) }
        // same as the real TunnelCoordinator.startTunnelInternal
        val runConfig = XtEndpointOverrides.apply(config.id, config.getConfig())
        World.bringUp(config.id, config.name, runConfig)
        _actions.emit(TunnelActionEvent.Started(config.id, source))
    }
    private suspend fun stopInternal(id: Int, source: TunnelActionSource) {
        World.bringDown(id)
        _actions.emit(TunnelActionEvent.Stopped(id, source))
    }
}

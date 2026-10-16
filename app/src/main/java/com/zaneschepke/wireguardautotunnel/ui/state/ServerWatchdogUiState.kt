package com.zaneschepke.wireguardautotunnel.ui.state

import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelMode
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.xt.WatchdogRuntime
import com.zaneschepke.wireguardautotunnel.xt.XtEvent
import com.zaneschepke.wireguardautotunnel.xt.XtSettings

data class ServerWatchdogUiState(
    val isLoading: Boolean = true,
    val settings: XtSettings = XtSettings(),
    val runtime: WatchdogRuntime = WatchdogRuntime(),
    val events: List<XtEvent> = emptyList(),
    val tunnels: List<TunnelConfig> = emptyList(),
    val tunnelMode: TunnelMode = TunnelMode.VPN,
)

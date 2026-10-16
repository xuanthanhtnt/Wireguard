package com.zaneschepke.wireguardautotunnel.viewmodel

import androidx.lifecycle.ViewModel
import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.ui.state.ServerWatchdogUiState
import com.zaneschepke.wireguardautotunnel.xt.ProbeMethod
import com.zaneschepke.wireguardautotunnel.xt.ServerWatchdogCoordinator
import com.zaneschepke.wireguardautotunnel.xt.XtSettings
import com.zaneschepke.wireguardautotunnel.xt.XtSettingsRepository
import kotlinx.coroutines.flow.combine
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.viewmodel.orbitContainer

class ServerWatchdogViewModel(
    private val coordinator: ServerWatchdogCoordinator,
    private val xtRepository: XtSettingsRepository,
    private val tunnelRepository: TunnelRepository,
    private val settingsRepository: GeneralSettingRepository,
) : OrbitContainerHost<ServerWatchdogUiState, ServerWatchdogUiState, Nothing>, ViewModel() {

    override val container =
        orbitContainer<ServerWatchdogUiState, Nothing>(
            ServerWatchdogUiState(),
            buildSettings = { repeatOnSubscribedStopTimeout = 5000L },
        ) {
            combine(
                    xtRepository.flow,
                    coordinator.runtime,
                    xtRepository.eventsFlow,
                    tunnelRepository.userTunnelsFlow,
                    settingsRepository.flow,
                ) { settings, runtime, events, tunnels, general ->
                    ServerWatchdogUiState(
                        isLoading = false,
                        settings = settings,
                        runtime = runtime,
                        events = events,
                        tunnels = tunnels,
                        tunnelMode = general.tunnelMode,
                    )
                }
                .collect { newState -> reduce { newState } }
        }

    private fun update(transform: (XtSettings) -> XtSettings) = intent {
        xtRepository.update(transform)
    }

    fun setIpWatchEnabled(enabled: Boolean) = update { it.copy(ipWatchEnabled = enabled) }

    fun setIpWatchInterval(seconds: Int) = update { it.copy(ipWatchIntervalSec = seconds) }

    fun setUseDoh(enabled: Boolean) = update { it.copy(useDoh = enabled) }

    fun setAutoPauseEnabled(enabled: Boolean) = update { it.copy(autoPauseEnabled = enabled) }

    fun setPauseAfter(seconds: Int) = update { it.copy(pauseAfterSec = seconds) }

    fun setProbeInterval(seconds: Int) = update { it.copy(probeIntervalSec = seconds) }

    fun setProbeMethod(method: ProbeMethod) = update { it.copy(probeMethod = method) }

    fun setProbeTcpPort(port: Int) = update { it.copy(probeTcpPort = port.coerceIn(0, 65_535)) }

    fun setTrialTimeout(seconds: Int) = update { it.copy(trialTimeoutSec = seconds) }

    fun setSuspendKillSwitch(enabled: Boolean) = update { it.copy(suspendKillSwitch = enabled) }

    fun setNotifyEvents(enabled: Boolean) = update { it.copy(notifyEvents = enabled) }

    fun setIpSource(tunnelId: Int, source: String) = intent {
        xtRepository.setIpSource(tunnelId, source)
    }

    fun checkNow() = coordinator.checkNow()

    fun resumeNow() = coordinator.resumeNow()

    fun cancelPause() = coordinator.cancelPause()

    fun clearHistory() = intent { coordinator.clearHistory() }
}

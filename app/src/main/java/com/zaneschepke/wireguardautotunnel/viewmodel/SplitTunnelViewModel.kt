package com.zaneschepke.wireguardautotunnel.viewmodel

import androidx.lifecycle.ViewModel
import com.dokar.sonner.ToastType
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.repository.GlobalEffectRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.InstalledPackageRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.domain.sideeffect.GlobalSideEffect
import com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.splittunnel.state.SplitBehavior
import com.zaneschepke.wireguardautotunnel.ui.state.EditableConfig
import com.zaneschepke.wireguardautotunnel.ui.state.EditableInterface
import com.zaneschepke.wireguardautotunnel.ui.state.SplitTunnelUiState
import com.zaneschepke.wireguardautotunnel.util.StringValue
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.viewmodel.orbitContainer

class SplitTunnelViewModel(
    private val tunnelRepository: TunnelRepository,
    private val packageRepository: InstalledPackageRepository,
    private val globalEffectRepository: GlobalEffectRepository,
    val tunnelId: Int,
) : OrbitContainerHost<SplitTunnelUiState, SplitTunnelUiState, Nothing>, ViewModel() {

    override val container =
        orbitContainer<SplitTunnelUiState, Nothing>(
            SplitTunnelUiState(),
            buildSettings = { repeatOnSubscribedStopTimeout = 5000L },
        ) {
            val packagesFlow = packageRepository.installedPackages

            val tunnelsFlow = tunnelRepository.userTunnelsFlow

            val currentTunnelFlow =
                tunnelRepository.flow.map { list -> list.firstOrNull { it.id == tunnelId } }

            combine(packagesFlow, tunnelsFlow, currentTunnelFlow) { packages, tunnels, tunnel ->
                    val currentState = state

                    val config = tunnel?.getConfig()
                    val included = config?.`interface`?.allIncludedApps.orEmpty().toSet()
                    val excluded = config?.`interface`?.allExcludedApps.orEmpty().toSet()
                    val initialEnabled = included.isNotEmpty() || excluded.isNotEmpty()
                    val initialBehavior =
                        if (excluded.isNotEmpty()) SplitBehavior.EXCLUDE else SplitBehavior.INCLUDE

                    val isInitialized = currentState.tunnel != null

                    SplitTunnelUiState(
                        installedPackages = packages,
                        tunnels = tunnels.map { it.toSummary() },
                        tunnel = tunnel,
                        isLoading = packages.isEmpty(),
                        isSplitTunnelingEnabled =
                            if (isInitialized) currentState.isSplitTunnelingEnabled
                            else initialEnabled,
                        activeBehavior =
                            if (isInitialized) currentState.activeBehavior else initialBehavior,
                        includedPackages =
                            if (isInitialized) currentState.includedPackages else included,
                        excludedPackages =
                            if (isInitialized) currentState.excludedPackages else excluded,
                        selectedCopySourceTunnelId = currentState.selectedCopySourceTunnelId,
                    )
                }
                .collect { newState -> reduce { newState } }
        }

    suspend fun postSideEffect(globalSideEffect: GlobalSideEffect) {
        globalEffectRepository.post(globalSideEffect)
    }

    fun save() = intent {
        val tunnel = state.tunnel ?: return@intent
        val config = tunnel.getConfig()
        val editableConfig = EditableConfig.from(config)
        val editableInterface = EditableInterface.from(config.`interface`)
        val (included, excluded) =
            if (!state.isSplitTunnelingEnabled) {
                Pair(emptySet(), emptySet())
            } else {
                when (state.activeBehavior) {
                    SplitBehavior.INCLUDE -> Pair(state.includedPackages, emptySet<String>())
                    SplitBehavior.EXCLUDE -> Pair(emptySet<String>(), state.excludedPackages)
                }
            }
        val updatedInterface =
            editableInterface.copy(includedApplications = included, excludedApplications = excluded)
        val updatedProxyConfig = editableConfig.copy(`interface` = updatedInterface)
        val updatedConfig = updatedProxyConfig.buildConfig()
        tunnelRepository.save(
            tunnel.copy(quickConfig = updatedConfig.withName(tunnel.name).asQuickString())
        )
        postSideEffect(
            GlobalSideEffect.Snackbar(
                StringValue.StringResource(R.string.config_changes_saved),
                ToastType.Success,
            )
        )
        postSideEffect(GlobalSideEffect.PopBackStack)
    }

    fun setSplitTunnelingEnabled(enabled: Boolean) = intent {
        reduce { state.copy(isSplitTunnelingEnabled = enabled) }
    }

    fun setActiveBehavior(behavior: SplitBehavior) = intent {
        reduce { state.copy(activeBehavior = behavior) }
    }

    fun togglePackage(pkg: String, enabled: Boolean) = intent {
        reduce {
            when (state.activeBehavior) {
                SplitBehavior.INCLUDE -> {
                    val updated =
                        state.includedPackages.toMutableSet().apply {
                            if (enabled) add(pkg) else remove(pkg)
                        }
                    state.copy(includedPackages = updated)
                }
                SplitBehavior.EXCLUDE -> {
                    val updated =
                        state.excludedPackages.toMutableSet().apply {
                            if (enabled) add(pkg) else remove(pkg)
                        }
                    state.copy(excludedPackages = updated)
                }
            }
        }
    }

    fun applyCopySource() = intent {
        val id = state.selectedCopySourceTunnelId ?: return@intent

        val tunnel = tunnelRepository.getById(id) ?: return@intent

        val config = tunnel.getConfig()
        val included = config.`interface`.allIncludedApps.toSet()
        val excluded = config.`interface`.allExcludedApps.toSet()
        val enabled = included.isNotEmpty() || excluded.isNotEmpty()
        val behavior = if (excluded.isNotEmpty()) SplitBehavior.EXCLUDE else SplitBehavior.INCLUDE

        reduce {
            state.copy(
                isSplitTunnelingEnabled = enabled,
                activeBehavior = behavior,
                includedPackages = included,
                excludedPackages = excluded,
                selectedCopySourceTunnelId = null,
            )
        }
    }

    fun selectCopySource(tunnelId: Int?) = intent {
        reduce { state.copy(selectedCopySourceTunnelId = tunnelId) }
    }
}

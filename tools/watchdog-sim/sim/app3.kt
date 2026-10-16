package com.zaneschepke.wireguardautotunnel.core.tunnel
import com.wgtunnel.backend.state.BackendStatus
import com.zaneschepke.wireguardautotunnel.domain.model.LockdownSettings
import kotlinx.coroutines.flow.StateFlow
interface TunnelProvider {
    suspend fun stopTunnel(tunnelId: Int): Result<Unit>
    suspend fun setLockDown(settings: LockdownSettings): Result<Unit>
    suspend fun disableLockDown(): Result<Unit>
    suspend fun bounceTunnel(tunnelId: Int, withFreshResolution: Boolean): Boolean
    val backendStatus: StateFlow<BackendStatus>
}

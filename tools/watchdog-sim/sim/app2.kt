package com.zaneschepke.wireguardautotunnel.domain.repository
import com.zaneschepke.wireguardautotunnel.domain.model.*
import kotlinx.coroutines.flow.Flow
interface TunnelRepository {
    val userTunnelsFlow: Flow<List<TunnelConfig>>
    suspend fun getAll(): List<TunnelConfig>
    suspend fun save(tunnelConfig: TunnelConfig)
    suspend fun getById(id: Int): TunnelConfig?
}
interface GeneralSettingRepository {
    val flow: Flow<GeneralSettings>
    suspend fun getGeneralSettings(): GeneralSettings
}
interface LockdownSettingsRepository {
    suspend fun getLockdownSettings(): LockdownSettings
}

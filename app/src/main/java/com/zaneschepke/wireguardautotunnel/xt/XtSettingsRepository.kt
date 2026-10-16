package com.zaneschepke.wireguardautotunnel.xt

import androidx.datastore.preferences.core.stringPreferencesKey
import com.zaneschepke.wireguardautotunnel.data.DataStoreManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Stores WG VPN settings, the paused-tunnel state and the event history in DataStore. */
class XtSettingsRepository(private val dataStoreManager: DataStoreManager) {

    private val writeMutex = Mutex()

    val flow: Flow<XtSettings> =
        dataStoreManager
            .getFromStoreFlow(KEY_SETTINGS)
            .map { XtSettings.fromJson(it) }
            .catch {
                Timber.e(it)
                emitAll(flowOf(XtSettings()))
            }
            .distinctUntilChanged()

    val eventsFlow: Flow<List<XtEvent>> =
        dataStoreManager
            .getFromStoreFlow(KEY_EVENTS)
            .map { XtEvent.listFromJson(it) }
            .catch {
                Timber.e(it)
                emitAll(flowOf(emptyList()))
            }
            .distinctUntilChanged()

    suspend fun get(): XtSettings = XtSettings.fromJson(dataStoreManager.getFromStore(KEY_SETTINGS))

    suspend fun update(transform: (XtSettings) -> XtSettings) =
        writeMutex.withLock {
            val updated = transform(get())
            dataStoreManager.saveToDataStore(KEY_SETTINGS, updated.toJson())
        }

    suspend fun setIpSource(tunnelId: Int, source: String?) = update { current ->
        val clean = source?.trim().orEmpty()
        current.copy(
            ipSources =
                if (clean.isEmpty()) current.ipSources - tunnelId
                else current.ipSources + (tunnelId to clean)
        )
    }

    suspend fun getPause(): PauseState? =
        PauseState.fromJson(dataStoreManager.getFromStore(KEY_PAUSE))

    suspend fun setPause(state: PauseState?) =
        writeMutex.withLock {
            if (state == null) dataStoreManager.removeFromDataStore(KEY_PAUSE)
            else dataStoreManager.saveToDataStore(KEY_PAUSE, state.toJson())
        }

    suspend fun addEvent(event: XtEvent) =
        writeMutex.withLock {
            val current = XtEvent.listFromJson(dataStoreManager.getFromStore(KEY_EVENTS))
            val updated = (listOf(event) + current).take(XtEvent.MAX_EVENTS)
            dataStoreManager.saveToDataStore(KEY_EVENTS, XtEvent.listToJson(updated))
        }

    suspend fun clearEvents() =
        writeMutex.withLock { dataStoreManager.removeFromDataStore(KEY_EVENTS) }

    companion object {
        private val KEY_SETTINGS = stringPreferencesKey("XT_SETTINGS")
        private val KEY_PAUSE = stringPreferencesKey("XT_PAUSE_STATE")
        private val KEY_EVENTS = stringPreferencesKey("XT_EVENTS")
    }
}

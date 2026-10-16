package com.zaneschepke.wireguardautotunnel.data
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.*
class DataStoreManager {
    val store = MutableStateFlow<Map<String, Any?>>(emptyMap())
    suspend fun <T> saveToDataStore(key: Preferences.Key<T>, value: T) { store.update { it + (key.name to value) } }
    suspend fun <T> removeFromDataStore(key: Preferences.Key<T>) { store.update { it - key.name } }
    @Suppress("UNCHECKED_CAST")
    fun <T> getFromStoreFlow(key: Preferences.Key<T>): Flow<T?> = store.map { it[key.name] as T? }
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> getFromStore(key: Preferences.Key<T>): T? = store.value[key.name] as T?
}

package com.rfidunlock.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

/** Параметры подключения к ПК. */
data class ServerSettings(
    val host: String = "",
    /** Адрес в LAN — быстрый путь, пробуется до основного. Может быть пуст. */
    val lan: String = "",
    val port: Int = 5390,
    val token: String = "",
    val ztNetworkId: String = "",
    val ztMoonId: String = "",
    val ztRoots: String = "",
) {
    val isConfigured: Boolean get() = host.isNotBlank()
}

/** Репозиторий настроек подключения (DataStore). */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val TOKEN = stringPreferencesKey("token")
    }

    val settings: Flow<ServerSettings> = context.dataStore.data.map { prefs ->
        ServerSettings(
            host = prefs[Keys.HOST] ?: "",
            port = prefs[Keys.PORT] ?: 5390,
            token = prefs[Keys.TOKEN] ?: "",
        )
    }

    suspend fun update(host: String, port: Int, token: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HOST] = host.trim()
            prefs[Keys.PORT] = port
            prefs[Keys.TOKEN] = token.trim()
        }
    }
}

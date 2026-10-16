package com.comfyapp.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")
private val SERVER_URL = stringPreferencesKey("server_url")
private val RECENT_URLS = stringPreferencesKey("recent_urls") // '\n'-joined, newest first

class SettingsRepositoryImpl(private val context: Context) : SettingsRepository {
    override val serverUrl: Flow<String?> = context.settingsStore.data.map { it[SERVER_URL] }

    override val recentUrls: Flow<List<String>> = context.settingsStore.data.map { prefs ->
        prefs[RECENT_URLS]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    }

    override suspend fun setServerUrl(url: String) {
        context.settingsStore.edit { it[SERVER_URL] = url }
    }

    override suspend fun rememberUrl(url: String) {
        context.settingsStore.edit { prefs ->
            val cur = prefs[RECENT_URLS]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            prefs[RECENT_URLS] = (listOf(url) + cur.filter { it != url }).take(5).joinToString("\n")
        }
    }
}

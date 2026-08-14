package com.flupke.dsh.connect

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "dsh_host")

/** Persists the configured DSH host (plain DataStore; no secrets stored). */
class HostStore(private val context: Context) {

    private val keyUrl = stringPreferencesKey("base_url")

    val baseUrl: Flow<String?> = context.dataStore.data.map { it[keyUrl] }

    suspend fun save(url: String) {
        context.dataStore.edit { it[keyUrl] = url.trimEnd('/') }
    }

    suspend fun clear() {
        context.dataStore.edit { it.remove(keyUrl) }
    }
}

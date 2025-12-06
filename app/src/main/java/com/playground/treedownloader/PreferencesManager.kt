package com.playground.treedownloader

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesManager(private val context: Context) {
    companion object {
        private val IP_KEY = stringPreferencesKey("ip")
        private val PORT_KEY = stringPreferencesKey("port")
        private val FOLDER_KEY = stringPreferencesKey("folder")
    }

    val ip: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[IP_KEY] ?: "10.0.0.13"
    }

    val port: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PORT_KEY] ?: "1948"
    }

    val folder: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[FOLDER_KEY] ?: ""
    }

    suspend fun saveIp(ip: String) {
        context.dataStore.edit { preferences ->
            preferences[IP_KEY] = ip
        }
    }

    suspend fun savePort(port: String) {
        context.dataStore.edit { preferences ->
            preferences[PORT_KEY] = port
        }
    }

    suspend fun saveFolder(folder: String) {
        context.dataStore.edit { preferences ->
            preferences[FOLDER_KEY] = folder
        }
    }

    suspend fun getIp(): String {
        return context.dataStore.data.first()[IP_KEY] ?: "10.0.0.13"
    }

    suspend fun getPort(): String {
        return context.dataStore.data.first()[PORT_KEY] ?: "1948"
    }

    suspend fun getFolder(): String {
        return context.dataStore.data.first()[FOLDER_KEY] ?: ""
    }
}


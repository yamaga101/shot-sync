package jp.gmail.yamaga101.shotsync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "shot_sync_settings")

/**
 * 既定の Drive folder ID。アプリが初回起動した時点でこれが入力欄に出る。
 * yamaga101 が普段使ってる「EV Manager debug screenshots」フォルダ。
 */
const val DEFAULT_DRIVE_FOLDER_ID = "1DBOVzP2x8qS8ThsihMutfH_8IgzSMOWa"

object SettingsKeys {
    val DRIVE_FOLDER_ID = stringPreferencesKey("drive_folder_id")
    val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
    val LAST_UPLOADED_PATH = stringPreferencesKey("last_uploaded_path")
}

class Settings(private val context: Context) {
    val driveFolderId: Flow<String?> = context.dataStore.data.map {
        it[SettingsKeys.DRIVE_FOLDER_ID] ?: DEFAULT_DRIVE_FOLDER_ID
    }
    val autoSyncEnabled: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.AUTO_SYNC_ENABLED] ?: false }
    val lastUploadedPath: Flow<String?> = context.dataStore.data.map { it[SettingsKeys.LAST_UPLOADED_PATH] }

    suspend fun setDriveFolderId(id: String) =
        context.dataStore.edit { it[SettingsKeys.DRIVE_FOLDER_ID] = id }

    suspend fun setAutoSyncEnabled(enabled: Boolean) =
        context.dataStore.edit { it[SettingsKeys.AUTO_SYNC_ENABLED] = enabled }

    suspend fun setLastUploadedPath(path: String) =
        context.dataStore.edit { it[SettingsKeys.LAST_UPLOADED_PATH] = path }

    suspend fun snapshot(): Triple<String?, Boolean, String?> {
        val data = context.dataStore.data.first()
        return Triple(
            data[SettingsKeys.DRIVE_FOLDER_ID] ?: DEFAULT_DRIVE_FOLDER_ID,
            data[SettingsKeys.AUTO_SYNC_ENABLED] ?: false,
            data[SettingsKeys.LAST_UPLOADED_PATH],
        )
    }
}

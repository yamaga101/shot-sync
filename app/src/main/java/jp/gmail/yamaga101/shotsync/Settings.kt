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

/**
 * Camera (DCIM/Camera/) 用の既定 Drive folder ID。空欄保存中の user は
 * これに自動 fallback する。明示的に別 ID を保存している user は touch しない。
 */
const val DEFAULT_CAMERA_DRIVE_FOLDER_ID = "12y4EpdE_jXuXbi6E0rxpqUblA4t3rCLp"

object SettingsKeys {
    val DRIVE_FOLDER_ID = stringPreferencesKey("drive_folder_id")
    val CAMERA_DRIVE_FOLDER_ID = stringPreferencesKey("camera_drive_folder_id")
    val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
    val LAST_UPLOADED_PATH = stringPreferencesKey("last_uploaded_path")
    val SYNC_CAMERA_PHOTOS = booleanPreferencesKey("sync_camera_photos")
    val WIFI_ONLY = booleanPreferencesKey("wifi_only")
}

data class SettingsSnapshot(
    val driveFolderId: String?,
    val cameraDriveFolderId: String?,  // 空欄 → driveFolderId に fallback
    val autoSyncEnabled: Boolean,
    val syncCameraPhotos: Boolean,
    val wifiOnly: Boolean,
    val lastUploadedPath: String?,
) {
    /** source type に応じた upload 先 folder ID を返す。 */
    fun folderIdFor(source: SourceType): String? = when (source) {
        SourceType.SCREENSHOT -> driveFolderId
        SourceType.CAMERA -> cameraDriveFolderId?.takeIf { it.isNotBlank() } ?: driveFolderId
    }
}

enum class SourceType { SCREENSHOT, CAMERA }

class Settings(private val context: Context) {
    val driveFolderId: Flow<String?> = context.dataStore.data.map {
        it[SettingsKeys.DRIVE_FOLDER_ID] ?: DEFAULT_DRIVE_FOLDER_ID
    }
    val cameraDriveFolderId: Flow<String?> = context.dataStore.data.map {
        val v = it[SettingsKeys.CAMERA_DRIVE_FOLDER_ID]
        if (v.isNullOrBlank()) DEFAULT_CAMERA_DRIVE_FOLDER_ID else v
    }
    val autoSyncEnabled: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.AUTO_SYNC_ENABLED] ?: false }
    val syncCameraPhotos: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.SYNC_CAMERA_PHOTOS] ?: false }
    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.WIFI_ONLY] ?: true }
    val lastUploadedPath: Flow<String?> = context.dataStore.data.map { it[SettingsKeys.LAST_UPLOADED_PATH] }

    suspend fun setDriveFolderId(id: String) =
        context.dataStore.edit { it[SettingsKeys.DRIVE_FOLDER_ID] = id }

    suspend fun setCameraDriveFolderId(id: String) =
        context.dataStore.edit { it[SettingsKeys.CAMERA_DRIVE_FOLDER_ID] = id }

    suspend fun setAutoSyncEnabled(enabled: Boolean) =
        context.dataStore.edit { it[SettingsKeys.AUTO_SYNC_ENABLED] = enabled }

    suspend fun setSyncCameraPhotos(enabled: Boolean) =
        context.dataStore.edit { it[SettingsKeys.SYNC_CAMERA_PHOTOS] = enabled }

    suspend fun setWifiOnly(enabled: Boolean) =
        context.dataStore.edit { it[SettingsKeys.WIFI_ONLY] = enabled }

    suspend fun setLastUploadedPath(path: String) =
        context.dataStore.edit { it[SettingsKeys.LAST_UPLOADED_PATH] = path }

    suspend fun snapshot(): SettingsSnapshot {
        val data = context.dataStore.data.first()
        return SettingsSnapshot(
            driveFolderId = data[SettingsKeys.DRIVE_FOLDER_ID] ?: DEFAULT_DRIVE_FOLDER_ID,
            cameraDriveFolderId = data[SettingsKeys.CAMERA_DRIVE_FOLDER_ID]
                ?.takeIf { it.isNotBlank() } ?: DEFAULT_CAMERA_DRIVE_FOLDER_ID,
            autoSyncEnabled = data[SettingsKeys.AUTO_SYNC_ENABLED] ?: false,
            syncCameraPhotos = data[SettingsKeys.SYNC_CAMERA_PHOTOS] ?: false,
            wifiOnly = data[SettingsKeys.WIFI_ONLY] ?: true,
            lastUploadedPath = data[SettingsKeys.LAST_UPLOADED_PATH],
        )
    }
}

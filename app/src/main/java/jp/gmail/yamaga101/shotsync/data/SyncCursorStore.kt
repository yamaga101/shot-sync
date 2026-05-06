package jp.gmail.yamaga101.shotsync.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cursorDataStore by preferencesDataStore(name = "shot_sync_cursor")

/**
 * MediaStore catch-up cursor + heartbeat metadata。
 *
 * - lastSeenId: 最後に enqueue した MediaStore._ID。次回 catch-up で `_ID > lastSeenId` を拾う
 * - cursorInitialized: false なら現時点 max を baseline (過去捨て)
 * - lastTriggerAt: JobScheduler content trigger / boot / app-open のいずれかが発火した最終時刻
 * - lastScanAt: CatchUpWorker が走った最終時刻
 * - lastUploadAt: UploadWorker が成功した最終時刻
 *
 * 設定値とは分離 (settings.kt は user-facing、こちらは runtime state)。
 */
class SyncCursorStore(private val context: Context) {

    val lastSeenId: Flow<Long> = context.cursorDataStore.data.map { it[Keys.LAST_SEEN_ID] ?: -1L }
    val cursorInitialized: Flow<Boolean> = context.cursorDataStore.data.map { it[Keys.CURSOR_INITIALIZED] ?: false }
    val lastTriggerAt: Flow<Long> = context.cursorDataStore.data.map { it[Keys.LAST_TRIGGER_AT] ?: 0L }
    val lastScanAt: Flow<Long> = context.cursorDataStore.data.map { it[Keys.LAST_SCAN_AT] ?: 0L }
    val lastUploadAt: Flow<Long> = context.cursorDataStore.data.map { it[Keys.LAST_UPLOAD_AT] ?: 0L }

    suspend fun snapshot(): CursorSnapshot {
        val data = context.cursorDataStore.data.first()
        return CursorSnapshot(
            lastSeenId = data[Keys.LAST_SEEN_ID] ?: -1L,
            initialized = data[Keys.CURSOR_INITIALIZED] ?: false,
            lastTriggerAt = data[Keys.LAST_TRIGGER_AT] ?: 0L,
            lastScanAt = data[Keys.LAST_SCAN_AT] ?: 0L,
            lastUploadAt = data[Keys.LAST_UPLOAD_AT] ?: 0L,
        )
    }

    suspend fun setLastSeenId(id: Long, initialized: Boolean = true) {
        context.cursorDataStore.edit {
            it[Keys.LAST_SEEN_ID] = id
            it[Keys.CURSOR_INITIALIZED] = initialized
        }
    }

    suspend fun touchTrigger(now: Long = System.currentTimeMillis()) {
        context.cursorDataStore.edit { it[Keys.LAST_TRIGGER_AT] = now }
    }

    suspend fun touchScan(now: Long = System.currentTimeMillis()) {
        context.cursorDataStore.edit { it[Keys.LAST_SCAN_AT] = now }
    }

    suspend fun touchUpload(now: Long = System.currentTimeMillis()) {
        context.cursorDataStore.edit { it[Keys.LAST_UPLOAD_AT] = now }
    }

    private object Keys {
        val LAST_SEEN_ID = longPreferencesKey("last_seen_id")
        val CURSOR_INITIALIZED = booleanPreferencesKey("cursor_initialized")
        val LAST_TRIGGER_AT = longPreferencesKey("last_trigger_at")
        val LAST_SCAN_AT = longPreferencesKey("last_scan_at")
        val LAST_UPLOAD_AT = longPreferencesKey("last_upload_at")
    }
}

data class CursorSnapshot(
    val lastSeenId: Long,
    val initialized: Boolean,
    val lastTriggerAt: Long,
    val lastScanAt: Long,
    val lastUploadAt: Long,
)

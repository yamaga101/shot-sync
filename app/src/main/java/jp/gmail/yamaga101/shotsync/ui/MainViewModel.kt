package jp.gmail.yamaga101.shotsync.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import jp.gmail.yamaga101.shotsync.Settings
import jp.gmail.yamaga101.shotsync.UploadForegroundService
import jp.gmail.yamaga101.shotsync.drive.DriveAuth
import jp.gmail.yamaga101.shotsync.work.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UploadEntry(
    val name: String,
    val success: Boolean,
    val error: String? = null,
)

data class UiState(
    val signedInEmail: String? = null,
    val driveFolderId: String? = null,
    val autoSyncEnabled: Boolean = false,
    val syncCameraPhotos: Boolean = false,
    val wifiOnly: Boolean = true,
    val recent: List<UploadEntry> = emptyList(),
    val permissions: jp.gmail.yamaga101.shotsync.PermissionStatus =
        jp.gmail.yamaga101.shotsync.PermissionStatus(false, false, false),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                settings.driveFolderId,
                settings.autoSyncEnabled,
                settings.syncCameraPhotos,
                settings.wifiOnly,
            ) { id, auto, camera, wifi ->
                listOf(id, auto, camera, wifi)
            }.collect { values ->
                @Suppress("UNCHECKED_CAST")
                _state.value = _state.value.copy(
                    driveFolderId = values[0] as String?,
                    autoSyncEnabled = values[1] as Boolean,
                    syncCameraPhotos = values[2] as Boolean,
                    wifiOnly = values[3] as Boolean,
                )
            }
        }
        refreshSignInState()
    }

    fun refreshSignInState() {
        val context = getApplication<Application>()
        val account = DriveAuth.lastSignedInAccount(context)
        _state.value = _state.value.copy(signedInEmail = account?.email)
    }

    /** Activity の onResume などで呼んで、現在の OS-level 権限状態を反映する。 */
    fun refreshPermissions(context: Context) {
        val perms = jp.gmail.yamaga101.shotsync.Permissions.check(context)
        _state.value = _state.value.copy(permissions = perms)
    }

    fun signInIntent(context: Context): Intent = DriveAuth.signInIntent(context)

    fun onSignInResult(context: Context, data: Intent?) {
        viewModelScope.launch {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(data).result
                _state.value = _state.value.copy(signedInEmail = account?.email)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    recent = listOf(UploadEntry("(sign-in)", false, e.message)) + _state.value.recent
                )
            }
        }
    }

    fun signOut(context: Context) {
        viewModelScope.launch {
            DriveAuth.signInClient(context).signOut()
            _state.value = _state.value.copy(signedInEmail = null)
        }
    }

    fun saveFolderId(id: String, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            settings.setDriveFolderId(id)
            onResult(if (id.isBlank()) "folder ID をクリア" else "保存しました: ${id.take(8)}…")
        }
    }

    fun toggleAutoSync(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            settings.setAutoSyncEnabled(enabled)
            if (enabled) UploadForegroundService.start(context)
            else UploadForegroundService.stop(context)
        }
    }

    /** Camera 同期 toggle。ON 中の service は新 spec で restart 必要。 */
    fun toggleSyncCameraPhotos(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            settings.setSyncCameraPhotos(enabled)
            if (state.value.autoSyncEnabled) {
                // 観測 spec が変わったので service restart で baseline 取り直し
                UploadForegroundService.stop(context)
                UploadForegroundService.start(context)
            }
        }
    }

    /** Wi-Fi 限定 toggle。次回 enqueue から制約反映。既存 work には影響なし。 */
    fun toggleWifiOnly(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            settings.setWifiOnly(enabled)
            if (state.value.autoSyncEnabled) {
                UploadForegroundService.stop(context)
                UploadForegroundService.start(context)
            }
        }
    }

    /**
     * P1 確認用: MediaStore 上の最新 screenshot 1 枚を即時 enqueue。
     * URI を UploadWorker に渡して worker 側で cache copy + upload + cleanup する。
     * Drive 上のファイル名は displayName (prefix なし)。
     */
    fun uploadLatestScreenshot(context: Context, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val permErr = checkMediaPermission(context)
            if (permErr != null) {
                onResult(permErr)
                _state.value = _state.value.copy(
                    recent = listOf(UploadEntry("(perm)", false, permErr)) + _state.value.recent.take(19)
                )
                return@launch
            }
            val latest = withContext(Dispatchers.IO) {
                runCatching { findLatestScreenshot(context) }
                    .onFailure { Log.e("ShotSyncVM", "find latest failed", it) }
            }
            val target = latest.getOrNull()
            if (target == null) {
                val msg = latest.exceptionOrNull()?.let { "${it::class.java.simpleName}: ${it.message}" }
                    ?: "Screenshots が見つかりません (RELATIVE_PATH に Screenshots/ 含む image 0 件)"
                onResult(msg)
                _state.value = _state.value.copy(
                    recent = listOf(UploadEntry("(scan)", false, msg)) + _state.value.recent.take(19)
                )
                return@launch
            }
            val (id, uri, displayName) = target
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(UploadWorker.KEY_URI, uri.toString())
                        .putString(UploadWorker.KEY_DISPLAY_NAME, displayName)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            val unique = UploadWorker.UNIQUE_PREFIX + "manual-id-$id"
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork(unique, ExistingWorkPolicy.REPLACE, req)
            onResult("enqueue: $displayName")
            wm.getWorkInfoByIdLiveData(req.id).observeForever(object : androidx.lifecycle.Observer<WorkInfo?> {
                override fun onChanged(info: WorkInfo?) {
                    if (info == null) return
                    if (info.state.isFinished) {
                        val ok = info.state == WorkInfo.State.SUCCEEDED
                        val err = info.outputData.getString(UploadWorker.KEY_ERROR)
                        _state.value = _state.value.copy(
                            recent = listOf(UploadEntry(displayName, ok, err)) + _state.value.recent.take(19)
                        )
                        onResult(if (ok) "✔ uploaded: $displayName" else "✗ failed: ${err ?: "unknown"}")
                        wm.getWorkInfoByIdLiveData(req.id).removeObserver(this)
                    }
                }
            })
        }
    }

    private fun checkMediaPermission(context: Context): String? {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
            null
        } else {
            "画像読み取り権限がありません。設定 → アプリ → shot-sync → 権限 → 画像 → 許可してください"
        }
    }

    private fun findLatestScreenshot(context: Context): Triple<Long, Uri, String>? {
        val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%Screenshots/%")
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        context.contentResolver.query(collection, projection, selection, args, sortOrder)?.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                ?: "screenshot-$id.jpg"
            val itemUri = android.content.ContentUris.withAppendedId(collection, id)
            return Triple(id, itemUri, name)
        }
        return null
    }
}

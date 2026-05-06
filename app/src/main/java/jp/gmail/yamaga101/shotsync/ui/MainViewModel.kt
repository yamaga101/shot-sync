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
import jp.gmail.yamaga101.shotsync.DiagnosticsLog
import jp.gmail.yamaga101.shotsync.Settings
import jp.gmail.yamaga101.shotsync.data.SyncCursorStore
import jp.gmail.yamaga101.shotsync.drive.DriveAuth
import jp.gmail.yamaga101.shotsync.job.MediaStoreTriggerJobService
import jp.gmail.yamaga101.shotsync.work.CatchUpWorker
import jp.gmail.yamaga101.shotsync.work.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val cameraDriveFolderId: String? = null,
    val autoSyncEnabled: Boolean = false,
    val syncCameraPhotos: Boolean = false,
    val wifiOnly: Boolean = true,
    val recent: List<UploadEntry> = emptyList(),
    val permissions: jp.gmail.yamaga101.shotsync.PermissionStatus =
        jp.gmail.yamaga101.shotsync.PermissionStatus(false, false, false),
    /** JobScheduler content trigger / boot / app-open のいずれかが最後に発火した時刻 (ms epoch、0 = 未発火) */
    val lastTriggerAt: Long = 0L,
    /** CatchUpWorker が最後に走った時刻 */
    val lastScanAt: Long = 0L,
    /** UploadWorker が最後に成功した時刻 */
    val lastUploadAt: Long = 0L,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val cursorStore = SyncCursorStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                settings.driveFolderId,
                settings.cameraDriveFolderId,
                settings.autoSyncEnabled,
                settings.syncCameraPhotos,
                settings.wifiOnly,
            ) { id, cameraId, auto, camera, wifi ->
                listOf(id, cameraId, auto, camera, wifi)
            }.collect { values ->
                _state.value = _state.value.copy(
                    driveFolderId = values[0] as String?,
                    cameraDriveFolderId = values[1] as String?,
                    autoSyncEnabled = values[2] as Boolean,
                    syncCameraPhotos = values[3] as Boolean,
                    wifiOnly = values[4] as Boolean,
                )
            }
        }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                cursorStore.lastTriggerAt,
                cursorStore.lastScanAt,
                cursorStore.lastUploadAt,
            ) { trig, scan, upl -> Triple(trig, scan, upl) }
                .collect { (trig, scan, upl) ->
                    _state.value = _state.value.copy(
                        lastTriggerAt = trig,
                        lastScanAt = scan,
                        lastUploadAt = upl,
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

    /**
     * Activity の onResume などで呼んで OS-level 権限状態を反映 + catch-up 起動。
     * v0.3.0: 常時 FGS は廃止。代わりに JobScheduler content trigger を再 schedule
     * + CatchUpWorker を 1 回 enqueue (idempotent)。
     */
    fun refreshPermissions(context: Context) {
        val perms = jp.gmail.yamaga101.shotsync.Permissions.check(context)
        _state.value = _state.value.copy(permissions = perms)
        if (perms.allGreen) ensureAutoSyncWired(context)
    }

    /**
     * autoSync ON かつ permissions allGreen の時に毎回呼ぶ idempotent bootstrap。
     *  - MediaStore content trigger を再 schedule (JobScheduler 自身が同 ID を置換)
     *  - CatchUpWorker を 1 回 enqueue (アプリを開いた瞬間に missed image を回収)
     * 失敗しても黙って次の trigger / 次回 onResume で復旧する。
     */
    private fun ensureAutoSyncWired(context: Context) {
        viewModelScope.launch {
            val auto = settings.autoSyncEnabled.first()
            if (!auto) return@launch
            DiagnosticsLog.info("MainVM", "ensureAutoSyncWired: schedule trigger + enqueue catch-up")
            MediaStoreTriggerJobService.schedule(context)
            CatchUpWorker.enqueue(context)
        }
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

    fun saveCameraFolderId(id: String, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            settings.setCameraDriveFolderId(id)
            onResult(
                if (id.isBlank()) "Camera 用 folder クリア (Screenshots と同じ)"
                else "Camera folder 保存: ${id.take(8)}…"
            )
        }
    }

    fun toggleAutoSync(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            settings.setAutoSyncEnabled(enabled)
            if (enabled) {
                MediaStoreTriggerJobService.schedule(context)
                CatchUpWorker.enqueue(context)
            } else {
                MediaStoreTriggerJobService.cancel(context)
            }
        }
    }

    /** Camera 同期 toggle。trigger は単一 (MediaStore.Images 全体)、catch-up scope だけ
     * 変わるので、enqueue だけし直せばよい。restart 不要。 */
    fun toggleSyncCameraPhotos(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            settings.setSyncCameraPhotos(enabled)
            if (state.value.autoSyncEnabled) {
                CatchUpWorker.enqueue(context)
            }
        }
    }

    /** Wi-Fi 限定 toggle。次回 catch-up から制約反映。既存 work には影響なし。 */
    fun toggleWifiOnly(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            settings.setWifiOnly(enabled)
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

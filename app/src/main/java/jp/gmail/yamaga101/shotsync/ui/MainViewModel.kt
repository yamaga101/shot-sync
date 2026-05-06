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
import java.io.File

data class UploadEntry(
    val name: String,
    val success: Boolean,
    val error: String? = null,
)

data class UiState(
    val signedInEmail: String? = null,
    val driveFolderId: String? = null,
    val autoSyncEnabled: Boolean = false,
    val recent: List<UploadEntry> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Settings の変化を購読
        viewModelScope.launch {
            combine(settings.driveFolderId, settings.autoSyncEnabled) { id, enabled -> id to enabled }
                .collect { (id, enabled) ->
                    _state.value = _state.value.copy(driveFolderId = id, autoSyncEnabled = enabled)
                }
        }
        // 既存 sign-in account を反映 (アプリ起動時)
        refreshSignInState()
    }

    fun refreshSignInState() {
        val context = getApplication<Application>()
        val account = DriveAuth.lastSignedInAccount(context)
        _state.value = _state.value.copy(signedInEmail = account?.email)
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

    /**
     * P1 確認用: /Pictures/Screenshots/ の最新 1 枚を即時 enqueue。
     * Android 11+ scoped storage で File.listFiles() は使えないので MediaStore 経由で
     * URI を取得 → ContentResolver で app cache dir にコピー → そのファイル path を
     * UploadWorker に渡す。READ_MEDIA_IMAGES 権限必須。
     */
    fun uploadLatestScreenshot(context: Context, onResult: (String) -> Unit) {
        viewModelScope.launch {
            // 1. 権限チェック
            val permErr = checkMediaPermission(context)
            if (permErr != null) {
                onResult(permErr)
                _state.value = _state.value.copy(
                    recent = listOf(UploadEntry("(perm)", false, permErr)) + _state.value.recent.take(19)
                )
                return@launch
            }
            // 2. MediaStore で latest screenshot を copy。例外を全部 catch して見える化。
            val cached = withContext(Dispatchers.IO) {
                runCatching { copyLatestScreenshotToCache(context) }
                    .onFailure { Log.e("ShotSyncVM", "copy failed", it) }
            }
            val pair = cached.getOrNull()
            if (pair == null) {
                val msg = cached.exceptionOrNull()?.let { "${it::class.java.simpleName}: ${it.message}" }
                    ?: "Screenshots が見つかりません (RELATIVE_PATH に Screenshots/ 含む image 0 件)"
                onResult(msg)
                _state.value = _state.value.copy(
                    recent = listOf(UploadEntry("(scan)", false, msg)) + _state.value.recent.take(19)
                )
                return@launch
            }
            val (file, displayName) = pair
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    Data.Builder().putString(UploadWorker.KEY_LOCAL_PATH, file.absolutePath).build()
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            val unique = UploadWorker.UNIQUE_PREFIX + "manual-" + displayName
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

    private fun copyLatestScreenshotToCache(context: Context): Pair<File, String>? {
        val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        // RELATIVE_PATH の例: "Pictures/Screenshots/" / "DCIM/Screenshots/"
        // SQL の LIMIT 句は Android の ContentResolver で SQLException を起こす版があるので
        // sortOrder で並べて moveToFirst で先頭 1 件を取る。
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%Screenshots/%")
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        context.contentResolver.query(collection, projection, selection, args, sortOrder)?.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
            val itemUri = Uri.withAppendedPath(collection, id.toString())
            val cacheFile = File(context.cacheDir, "upload-$id-$name").apply {
                if (exists()) delete()
            }
            context.contentResolver.openInputStream(itemUri)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            return cacheFile to name
        }
        return null
    }
}

package jp.gmail.yamaga101.shotsync.ui

import android.app.Application
import android.content.Context
import android.content.Intent
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
import jp.gmail.yamaga101.shotsync.observer.ScreenshotObserver
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

    fun saveFolderId(id: String) {
        viewModelScope.launch { settings.setDriveFolderId(id) }
    }

    fun toggleAutoSync(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            settings.setAutoSyncEnabled(enabled)
            if (enabled) UploadForegroundService.start(context)
            else UploadForegroundService.stop(context)
        }
    }

    /** P1 確認用: /Pictures/Screenshots/ の最新 1 枚を即時 enqueue。 */
    fun uploadLatestScreenshot(context: Context, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val latest = withContext(Dispatchers.IO) {
                ScreenshotObserver.screenshotsFolder()
                    .listFiles()
                    ?.filter { it.isFile }
                    ?.maxByOrNull { it.lastModified() }
            }
            if (latest == null) {
                onResult("Screenshots フォルダに画像がありません")
                return@launch
            }
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    Data.Builder().putString(UploadWorker.KEY_LOCAL_PATH, latest.absolutePath).build()
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            val unique = UploadWorker.UNIQUE_PREFIX + "manual-" + latest.name
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork(unique, ExistingWorkPolicy.REPLACE, req)
            onResult("enqueue: ${latest.name}")
            // result 観測
            wm.getWorkInfoByIdLiveData(req.id).observeForever(object : androidx.lifecycle.Observer<WorkInfo?> {
                override fun onChanged(info: WorkInfo?) {
                    if (info == null) return
                    if (info.state.isFinished) {
                        val ok = info.state == WorkInfo.State.SUCCEEDED
                        val err = info.outputData.getString(UploadWorker.KEY_ERROR)
                        _state.value = _state.value.copy(
                            recent = listOf(UploadEntry(latest.name, ok, err)) + _state.value.recent.take(19)
                        )
                        onResult(if (ok) "✔ uploaded: ${latest.name}" else "✗ failed: ${err ?: "unknown"}")
                        wm.getWorkInfoByIdLiveData(req.id).removeObserver(this)
                    }
                }
            })
        }
    }
}

package jp.gmail.yamaga101.shotsync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import jp.gmail.yamaga101.shotsync.Settings
import jp.gmail.yamaga101.shotsync.drive.DriveAuth
import jp.gmail.yamaga101.shotsync.drive.DriveUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString(KEY_LOCAL_PATH) ?: return@withContext Result.failure(
            Data.Builder().putString(KEY_ERROR, "no path").build()
        )
        val file = File(path)
        if (!file.exists()) return@withContext Result.failure(
            Data.Builder().putString(KEY_ERROR, "file gone: $path").build()
        )

        val account = DriveAuth.lastSignedInAccount(applicationContext)
            ?: return@withContext Result.failure(
                Data.Builder().putString(KEY_ERROR, "not signed in").build()
            )

        val (folderId, _, _) = Settings(applicationContext).snapshot()
        if (folderId.isNullOrBlank()) return@withContext Result.failure(
            Data.Builder().putString(KEY_ERROR, "no folder id").build()
        )

        try {
            val drive = DriveAuth.driveClient(applicationContext, account)
            val driveId = DriveUploader.uploadFile(drive, file, folderId)
            Settings(applicationContext).setLastUploadedPath(path)
            Result.success(
                Data.Builder()
                    .putString(KEY_DRIVE_FILE_ID, driveId)
                    .putString(KEY_LOCAL_PATH, path)
                    .build()
            )
        } catch (e: Exception) {
            // 一時的なエラーは retry。永続的 (auth 切れ等) は failure。
            val msg = e.message ?: e::class.java.simpleName
            if (runAttemptCount < 3 && msg.contains("network", true)) Result.retry()
            else Result.failure(Data.Builder().putString(KEY_ERROR, msg).build())
        }
    }

    companion object {
        const val KEY_LOCAL_PATH = "local_path"
        const val KEY_DRIVE_FILE_ID = "drive_file_id"
        const val KEY_ERROR = "error"
        const val UNIQUE_PREFIX = "shot-sync-upload-"
    }
}

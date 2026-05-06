package jp.gmail.yamaga101.shotsync.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import jp.gmail.yamaga101.shotsync.DiagnosticsLog
import jp.gmail.yamaga101.shotsync.Settings
import jp.gmail.yamaga101.shotsync.SourceType
import jp.gmail.yamaga101.shotsync.drive.DriveAuth
import jp.gmail.yamaga101.shotsync.drive.DriveUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 1 個の screenshot を Drive にアップロードする WorkRequest。
 * 入力は MediaStore URI (P3 ContentObserver 経路) または旧 KEY_LOCAL_PATH
 * (互換、廃止予定) のいずれか。
 *
 * doWork:
 *  1. URI を ContentResolver で開く → app cacheDir に displayName でコピー
 *  2. DriveAuth で sign-in account 取得 → drive client 構築
 *  3. DriveUploader.uploadFile (displayName 指定で prefix なしで Drive に保存)
 *  4. cache 削除
 *
 * retry: network 系 fail のみ 3 回まで。それ以外は failure。
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uriStr = inputData.getString(KEY_URI)
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "screenshot.jpg"
        val legacyPath = inputData.getString(KEY_LOCAL_PATH)
        val source = inputData.getString(KEY_SOURCE)?.let {
            runCatching { SourceType.valueOf(it) }.getOrDefault(SourceType.SCREENSHOT)
        } ?: SourceType.SCREENSHOT
        DiagnosticsLog.info("Worker", "start: $displayName ($source)")

        val account = DriveAuth.lastSignedInAccount(applicationContext)
            ?: return@withContext fail("not signed in").also {
                DiagnosticsLog.error("Worker", "abort: not signed in")
            }

        val snapshot = Settings(applicationContext).snapshot()
        val folderId = snapshot.folderIdFor(source)
        if (folderId.isNullOrBlank()) return@withContext fail("no folder id for source=$source").also {
            DiagnosticsLog.error("Worker", "abort: no folder id for source=$source")
        }

        // cache 上の作業ファイル。upload 後に必ず消す。
        val cacheFile: File = try {
            when {
                uriStr != null -> copyUriToCache(Uri.parse(uriStr), displayName)
                legacyPath != null -> File(legacyPath).also {
                    if (!it.exists()) return@withContext fail("file gone: $legacyPath")
                }
                else -> return@withContext fail("no input (uri / local_path)")
            }
        } catch (e: Exception) {
            return@withContext fail("copy failed: ${e.message ?: e::class.simpleName}")
        }

        try {
            val drive = DriveAuth.driveClient(applicationContext, account)
            val driveId = DriveUploader.uploadFile(drive, cacheFile, folderId, displayName)
            Settings(applicationContext).setLastUploadedPath(displayName)
            DiagnosticsLog.info("Worker", "✔ uploaded: $displayName (driveId=${driveId.take(12)}…)")
            Result.success(
                Data.Builder()
                    .putString(KEY_DRIVE_FILE_ID, driveId)
                    .putString(KEY_DISPLAY_NAME, displayName)
                    .build()
            )
        } catch (e: Exception) {
            val msg = e.message ?: e::class.java.simpleName
            if (runAttemptCount < 3 && msg.contains("network", true)) {
                DiagnosticsLog.warn("Worker", "retry ($runAttemptCount): $msg")
                Result.retry()
            } else {
                DiagnosticsLog.error("Worker", "✗ failed: $msg")
                fail(msg)
            }
        } finally {
            // cache のみ削除 (uriStr があった場合のみ)。legacy path は触らない。
            if (uriStr != null && cacheFile.exists()) cacheFile.delete()
        }
    }

    private fun copyUriToCache(uri: Uri, displayName: String): File {
        val cacheFile = File(applicationContext.cacheDir, "shot-${System.currentTimeMillis()}-$displayName")
        if (cacheFile.exists()) cacheFile.delete()
        applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("openInputStream returned null for $uri")
        return cacheFile
    }

    private fun fail(msg: String): Result = Result.failure(
        Data.Builder().putString(KEY_ERROR, msg).build()
    )

    companion object {
        const val KEY_URI = "uri"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_SOURCE = "source"  // SourceType.name
        const val KEY_LOCAL_PATH = "local_path"  // legacy
        const val KEY_DRIVE_FILE_ID = "drive_file_id"
        const val KEY_ERROR = "error"
        const val UNIQUE_PREFIX = "shot-sync-upload-"
    }
}

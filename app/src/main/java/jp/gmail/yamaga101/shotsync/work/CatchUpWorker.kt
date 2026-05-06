package jp.gmail.yamaga101.shotsync.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.gmail.yamaga101.shotsync.DiagnosticsLog
import jp.gmail.yamaga101.shotsync.Settings
import jp.gmail.yamaga101.shotsync.data.MediaScanner
import jp.gmail.yamaga101.shotsync.data.SyncCursorStore
import jp.gmail.yamaga101.shotsync.data.WatchSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * MediaStore catch-up runner。JobScheduler trigger / boot / app-open のいずれから
 * 起動されても同じことをする:
 *
 *  1. Settings から WatchSpec を組む (autoSync OFF なら何もせず success)
 *  2. cursor 未初期化 or `currentMaxId < persistedId` (factory reset / app reinstall)
 *     なら現時点 max を baseline 保存して終了 (過去捨て)
 *  3. `_ID > lastSeenId` の image を MediaStore から拾う
 *  4. 各 image を `enqueueUniqueWork(KEEP)` で UploadWorker に渡す
 *     (auto/manual で同 unique key 使用、二重 enqueue 防止)
 *  5. cursor を最大 _ID で更新
 *
 * 並列起動時の race を防ぐため module-level Mutex で guard。
 */
class CatchUpWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runMutex.withLock {
            try {
                runCatchUp()
                Result.success()
            } catch (e: Throwable) {
                DiagnosticsLog.error(TAG, "catch-up failed: ${e::class.simpleName}: ${e.message}")
                Result.failure()
            }
        }
    }

    private suspend fun runCatchUp() {
        val ctx = applicationContext
        val cursorStore = SyncCursorStore(ctx)
        cursorStore.touchScan()

        val settings = Settings(ctx).snapshot()
        if (!settings.autoSyncEnabled) {
            DiagnosticsLog.info(TAG, "skip: autoSync OFF")
            return
        }
        val spec = WatchSpec(
            includeScreenshots = true,  // Screenshots は autoSync ON 時は常に対象
            includeCameraPhotos = settings.syncCameraPhotos,
        )
        if (!spec.anyEnabled) {
            DiagnosticsLog.warn(TAG, "skip: no watch target")
            return
        }

        val cursor = cursorStore.snapshot()
        val currentMax = MediaScanner.currentMaxId(ctx, spec)

        if (!cursor.initialized || currentMax < cursor.lastSeenId) {
            cursorStore.setLastSeenId(currentMax, initialized = true)
            DiagnosticsLog.info(
                TAG,
                "baseline set: lastSeenId=$currentMax (init=${cursor.initialized}, prev=${cursor.lastSeenId})",
            )
            return
        }

        val newImages = MediaScanner.queryAfter(ctx, spec, cursor.lastSeenId)
        if (newImages.isEmpty()) {
            DiagnosticsLog.info(TAG, "no new images since id=${cursor.lastSeenId}")
            return
        }

        val wm = WorkManager.getInstance(ctx)
        for (item in newImages) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(UploadWorker.KEY_URI, item.uri.toString())
                        .putString(UploadWorker.KEY_DISPLAY_NAME, item.name)
                        .putString(UploadWorker.KEY_SOURCE, item.source.name)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                        )
                        .build()
                )
                .build()
            val unique = UploadWorker.UNIQUE_PREFIX + "id-${item.id}"
            wm.enqueueUniqueWork(unique, ExistingWorkPolicy.KEEP, req)
        }
        val maxId = newImages.maxOf { it.id }
        cursorStore.setLastSeenId(maxId, initialized = true)
        DiagnosticsLog.info(
            TAG,
            "enqueued ${newImages.size} (cursor ${cursor.lastSeenId} → $maxId)",
        )
    }

    companion object {
        private const val TAG = "CatchUpWorker"
        const val UNIQUE_NAME = "shot-sync-catchup"

        private val runMutex = Mutex()

        /**
         * idempotent な enqueue。既に走っていれば KEEP で何もしない。
         * 通常 work (= Doze 中は走らないが OS が wake すれば即実行)。
         */
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<CatchUpWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, req)
        }
    }
}

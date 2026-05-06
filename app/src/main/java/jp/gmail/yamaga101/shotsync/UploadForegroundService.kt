package jp.gmail.yamaga101.shotsync

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import jp.gmail.yamaga101.shotsync.observer.MediaStoreScreenshotObserver
import jp.gmail.yamaga101.shotsync.observer.WatchSpec
import jp.gmail.yamaga101.shotsync.work.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 端末再起動・アプリ swipe-out 後も /Pictures/Screenshots/ を watch するための
 * Foreground service。検知したら WorkManager に upload を enqueue する。
 *
 * 監視は MediaStore ContentObserver 経由。Android 11+ scoped storage 環境で
 * FileObserver は使えないため。
 */
class UploadForegroundService : Service() {

    private var observer: MediaStoreScreenshotObserver? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wifiOnly: Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLog.info(TAG, "onCreate")
        try {
            startForegroundCompat()
            DiagnosticsLog.info(TAG, "startForeground OK")
        } catch (e: Exception) {
            DiagnosticsLog.error(TAG, "startForeground FAILED: ${e::class.simpleName}: ${e.message}")
            stopSelf()
            return
        }
        // 設定を読んでから observer を起動
        scope.launch {
            try {
                val s = jp.gmail.yamaga101.shotsync.Settings(this@UploadForegroundService).snapshot()
                wifiOnly = s.wifiOnly
                val spec = WatchSpec(
                    includeScreenshots = true,  // auto sync ON 時は常に screenshots を見る
                    includeCameraPhotos = s.syncCameraPhotos,
                )
                startObserving(spec)
                DiagnosticsLog.info(TAG, "observer started (camera=${s.syncCameraPhotos}, wifiOnly=${s.wifiOnly})")
            } catch (e: Exception) {
                DiagnosticsLog.error(TAG, "startObserving FAILED: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, ShotSyncApp.CHANNEL_FOREGROUND)
            .setContentTitle("shot-sync 監視中")
            .setContentText("Screenshots を Drive に自動転送")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0,
        )
    }

    private fun startObserving(spec: WatchSpec) {
        val handler = Handler(Looper.getMainLooper())
        observer = MediaStoreScreenshotObserver(this, handler, spec) { id, uri, name, source ->
            DiagnosticsLog.info(TAG, "observer detected new image id=$id name=$name source=$source")
            enqueueUpload(id, uri, name, source)
        }.also { it.start() }
    }

    private fun enqueueUpload(id: Long, uri: Uri, displayName: String, source: jp.gmail.yamaga101.shotsync.SourceType) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(UploadWorker.KEY_URI, uri.toString())
                    .putString(UploadWorker.KEY_DISPLAY_NAME, displayName)
                    .putString(UploadWorker.KEY_SOURCE, source.name)
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                    )
                    .build()
            )
            .build()
        val unique = UploadWorker.UNIQUE_PREFIX + "id-$id"
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(unique, ExistingWorkPolicy.KEEP, req)
        DiagnosticsLog.info(TAG, "enqueued worker: $displayName (id=$id)")
    }

    override fun onDestroy() {
        DiagnosticsLog.info(TAG, "onDestroy")
        observer?.stop()
        observer = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    companion object {
        private const val TAG = "UploadFgService"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, UploadForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UploadForegroundService::class.java))
        }
    }
}

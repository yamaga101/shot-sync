package jp.gmail.yamaga101.shotsync

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import jp.gmail.yamaga101.shotsync.observer.ScreenshotObserver
import jp.gmail.yamaga101.shotsync.work.UploadWorker

/**
 * 端末再起動・アプリ swipe-out 後も /Pictures/Screenshots/ を watch するための
 * Foreground service。検知したら WorkManager に upload を enqueue する。
 */
class UploadForegroundService : Service() {

    private var observer: ScreenshotObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        startObserving()
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
            .setContentText("/Pictures/Screenshots/ を watch 中")
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

    private fun startObserving() {
        val folder = ScreenshotObserver.screenshotsFolder()
        if (!folder.exists()) {
            Log.w(TAG, "screenshots folder not found: $folder")
        }
        observer = ScreenshotObserver(folder) { file ->
            enqueueUpload(file.absolutePath)
        }.also { it.startWatching() }
        Log.i(TAG, "watching: $folder")
    }

    private fun enqueueUpload(path: String) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(UploadWorker.KEY_LOCAL_PATH, path)
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        val unique = UploadWorker.UNIQUE_PREFIX + path.hashCode().toString()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(unique, ExistingWorkPolicy.KEEP, req)
        Log.i(TAG, "enqueued: $path (workId=$unique)")
    }

    override fun onDestroy() {
        observer?.stopWatching()
        observer = null
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

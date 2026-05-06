package jp.gmail.yamaga101.shotsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import jp.gmail.yamaga101.shotsync.job.MediaStoreTriggerJobService
import jp.gmail.yamaga101.shotsync.work.CatchUpWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 端末再起動 / アプリ更新時の bootstrap:
 *  1. `MediaStoreTriggerJobService` を再 schedule (boot で消える)
 *  2. `CatchUpWorker` を 1 度走らせて起動中に追加された image を回収
 *
 * v0.2.0 までは UploadForegroundService.start を呼んでいたが、Android 15 / target=35 で
 * `BOOT_COMPLETED` から `dataSync` FGS は `ForegroundServiceStartNotAllowedException`
 * になるため廃止。FGS そのものも v0.3.0 で削除済。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = Settings(context).autoSyncEnabled.first()
                if (!enabled) {
                    DiagnosticsLog.info(TAG, "boot/replace: autoSync OFF, skip")
                    return@launch
                }
                MediaStoreTriggerJobService.schedule(context)
                CatchUpWorker.enqueue(context)
                DiagnosticsLog.info(TAG, "boot/replace: scheduled trigger + enqueued catch-up (action=${intent.action})")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}

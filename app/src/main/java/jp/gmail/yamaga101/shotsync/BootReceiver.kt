package jp.gmail.yamaga101.shotsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 端末起動時、auto sync が有効なら foreground service を立て直す。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // BroadcastReceiver は async 処理に向かないが、ここは「sync ON?」を読んで
        // 1 度 service を立てるだけなので goAsync で許容。
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = Settings(context).autoSyncEnabled.first()
                if (enabled) UploadForegroundService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}

package jp.gmail.yamaga101.shotsync.job

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.getSystemService
import jp.gmail.yamaga101.shotsync.DiagnosticsLog
import jp.gmail.yamaga101.shotsync.data.SyncCursorStore
import jp.gmail.yamaga101.shotsync.work.CatchUpWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * MediaStore.Images.Media.EXTERNAL_CONTENT_URI の content trigger を受けて
 * CatchUpWorker を enqueue する JobService。
 *
 * Android 7.0 で `ACTION_NEW_PICTURE` が廃止された際に提供された official 経路。
 * `JobInfo.TriggerContentUri` は process が dead でも OS が wake してくれる。
 *
 * 重要: TriggerContentUri は setPeriodic / setPersisted と互換性がない。
 * onStartJob 内で次の Job を再 schedule してから jobFinished する必要がある。
 *
 * `getTriggeredContentUris()` は信頼しない (50 URI 超過時 null、deadline trigger 時 null)。
 * "something changed; run scanner" として扱う。
 */
class MediaStoreTriggerJobService : JobService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            try {
                SyncCursorStore(applicationContext).touchTrigger()
                DiagnosticsLog.info(TAG, "trigger fired (uris=${params.triggeredContentUris?.size ?: 0})")
                CatchUpWorker.enqueue(applicationContext)
                schedule(applicationContext)
            } catch (e: Throwable) {
                DiagnosticsLog.error(TAG, "onStartJob failed: ${e::class.simpleName}: ${e.message}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true  // async work pending
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // 次回 schedule 済みなので reschedule 不要
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MediaTriggerJob"
        private const val JOB_ID = 0x53484F54  // "SHOT" magic

        /** idempotent な schedule。既に同 ID の job が存在すれば JobScheduler が置換する。 */
        fun schedule(context: Context) {
            val js = context.getSystemService<JobScheduler>() ?: run {
                DiagnosticsLog.error(TAG, "JobScheduler not available")
                return
            }
            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, MediaStoreTriggerJobService::class.java),
            )
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    )
                )
                .also { b ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        // 同じ trigger を 1 秒以内に何度も呼ばれた時の合体時間
                        b.setTriggerContentUpdateDelay(1_000L)
                        // trigger 後最大何秒待ってから job を起こすか
                        b.setTriggerContentMaxDelay(10_000L)
                    }
                }
                .build()
            val rc = js.schedule(info)
            if (rc != JobScheduler.RESULT_SUCCESS) {
                DiagnosticsLog.error(TAG, "schedule failed: rc=$rc")
            }
        }

        fun cancel(context: Context) {
            context.getSystemService<JobScheduler>()?.cancel(JOB_ID)
        }
    }
}

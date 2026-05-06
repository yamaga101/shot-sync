package jp.gmail.yamaga101.shotsync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.getSystemService

class ShotSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsLog.attach(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService<NotificationManager>() ?: return
        val resultChannel = NotificationChannel(
            CHANNEL_RESULTS,
            "Upload results",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "アップロード成功 / 失敗" }
        nm.createNotificationChannels(listOf(resultChannel))
    }

    companion object {
        const val CHANNEL_RESULTS = "results"
    }
}

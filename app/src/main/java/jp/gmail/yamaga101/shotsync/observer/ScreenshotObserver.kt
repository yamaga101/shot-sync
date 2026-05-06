package jp.gmail.yamaga101.shotsync.observer

import android.os.Environment
import android.os.FileObserver
import android.util.Log
import java.io.File

/**
 * `/Pictures/Screenshots/` (or fallback) に新規ファイルが落ちたら
 * `onNewScreenshot` を呼ぶ。Android 13+ では FileObserver の挙動が
 * scoped storage の影響を受けるが、Pictures/Screenshots は MediaStore
 * 経由でアクセス可。FileObserver で簡易検知する v0.1。
 *
 * v0.1 の制約: アプリ自身が image 読込権限を持つこと前提。実機で
 * 取れない場合は ContentObserver(MediaStore.Images) ベースに切替予定。
 */
class ScreenshotObserver(
    folder: File,
    private val onNewScreenshot: (File) -> Unit,
) : FileObserver(folder, CREATE or CLOSE_WRITE or MOVED_TO) {

    private val targetFolder = folder

    override fun onEvent(event: Int, path: String?) {
        if (path.isNullOrBlank()) return
        val masked = event and ALL_EVENTS
        if (masked != CREATE && masked != CLOSE_WRITE && masked != MOVED_TO) return
        val f = File(targetFolder, path)
        if (!f.exists() || f.length() == 0L) return
        if (!isImage(path)) return
        Log.i(TAG, "new screenshot: $path event=$masked")
        onNewScreenshot(f)
    }

    private fun isImage(name: String): Boolean =
        name.endsWith(".jpg", true) ||
        name.endsWith(".jpeg", true) ||
        name.endsWith(".png", true) ||
        name.endsWith(".webp", true)

    companion object {
        private const val TAG = "ScreenshotObserver"

        fun screenshotsFolder(): File {
            // Samsung One UI 8 系は /Pictures/Screenshots/。古い端末は /DCIM/Screenshots/ もあるので
            // 両方候補にして存在するものを返す。
            val pictures = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Screenshots"
            )
            if (pictures.exists()) return pictures
            val dcim = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "Screenshots"
            )
            return if (dcim.exists()) dcim else pictures
        }
    }
}

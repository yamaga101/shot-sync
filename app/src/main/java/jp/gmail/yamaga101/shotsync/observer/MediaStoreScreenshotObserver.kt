package jp.gmail.yamaga101.shotsync.observer

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import jp.gmail.yamaga101.shotsync.DiagnosticsLog

/**
 * MediaStore.Images に対する ContentObserver。`/Pictures/Screenshots/` 配下に
 * 新規 image (= スクショ) が追加されると `onNewScreenshot(id, uri, displayName)`
 * を呼ぶ。Android 11+ scoped storage 環境で FileObserver が使えないため、
 * これが正規ルート。
 *
 * 実装メモ:
 * - 起動時に現時点の最大 ID を `lastSeenId` に記録 (それ以前は無視)
 * - onChange で全 image の変更通知が来るので、`_ID > lastSeenId` の selection
 *   で新規分のみ取得 → callback → lastSeenId を更新
 * - RELATIVE_PATH に "Screenshots/" を含むものだけに絞る (DCIM/Screenshots と
 *   Pictures/Screenshots 両方カバー)
 */
class MediaStoreScreenshotObserver(
    private val context: Context,
    handler: Handler,
    private val onNewScreenshot: (id: Long, uri: Uri, displayName: String) -> Unit,
) : ContentObserver(handler) {

    @Volatile
    private var lastSeenId: Long = -1L

    fun start() {
        lastSeenId = currentMaxScreenshotId()
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            this,
        )
        DiagnosticsLog.info(TAG, "registered, lastSeenId=$lastSeenId")
    }

    fun stop() {
        try {
            context.contentResolver.unregisterContentObserver(this)
        } catch (e: Exception) {
            Log.w(TAG, "unregister failed", e)
        }
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        DiagnosticsLog.info(TAG, "onChange uri=$uri")
        try {
            scanForNew()
        } catch (e: Exception) {
            DiagnosticsLog.error(TAG, "scanForNew failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun currentMaxScreenshotId(): Long {
        val proj = arrayOf(MediaStore.Images.Media._ID)
        val sel = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%Screenshots/%")
        val sort = "${MediaStore.Images.Media._ID} DESC"
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args, sort
        )?.use { c ->
            if (c.moveToFirst()) c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            else -1L
        } ?: -1L
    }

    private fun scanForNew() {
        if (lastSeenId < 0) {
            // start() 前に onChange 来た場合は初期化だけして抜ける
            lastSeenId = currentMaxScreenshotId()
            return
        }
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        val sel =
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media._ID} > ?"
        val args = arrayOf("%Screenshots/%", lastSeenId.toString())
        val sort = "${MediaStore.Images.Media._ID} ASC"
        val newOnes = mutableListOf<Triple<Long, Uri, String>>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args, sort
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: "screenshot-$id.jpg"
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id,
                )
                newOnes.add(Triple(id, uri, name))
            }
        }
        if (newOnes.isEmpty()) {
            DiagnosticsLog.info(TAG, "scan: no new screenshots since id=$lastSeenId")
        }
        for ((id, uri, name) in newOnes) {
            try {
                onNewScreenshot(id, uri, name)
            } catch (e: Exception) {
                DiagnosticsLog.error(TAG, "callback threw: ${e::class.simpleName}: ${e.message}")
            }
            if (id > lastSeenId) lastSeenId = id
        }
    }

    companion object {
        private const val TAG = "ScreenshotMediaObserver"
    }
}

package jp.gmail.yamaga101.shotsync.observer

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.provider.MediaStore
import jp.gmail.yamaga101.shotsync.DiagnosticsLog

/**
 * 何を watch するかの宣言。Settings 由来の boolean 2 個から構築する。
 *
 * - [includeScreenshots]: `RELATIVE_PATH LIKE '%Screenshots/%'`
 * - [includeCameraPhotos]: `RELATIVE_PATH LIKE '%DCIM/Camera/%' OR BUCKET_DISPLAY_NAME='Camera'`
 *   (端末・camera app 互換のため BUCKET fallback を併用 — GPT-5.5 提案)
 */
data class WatchSpec(
    val includeScreenshots: Boolean,
    val includeCameraPhotos: Boolean,
) {
    val anyEnabled: Boolean get() = includeScreenshots || includeCameraPhotos
}

/**
 * MediaStore.Images に対する ContentObserver。新規 image (= スクショ / 撮影写真)
 * が追加されると `onNewScreenshot(id, uri, displayName)` を呼ぶ。
 *
 * Android 11+ scoped storage で FileObserver が使えないためこれが正規ルート。
 *
 * 起動時に `lastSeenId` を MediaStore の最大 ID に設定し、それより新しい _ID
 * のみ拾う。同じ change 通知を複数回受け取っても重複 enqueue されない。
 *
 * 採用 filter (L4 council 結果):
 * - RELATIVE_PATH または BUCKET_DISPLAY_NAME ベースで watch path を判定
 * - MIME_TYPE は `image/jpeg|png|heic|heif|webp|gif` のみ (RAW 除外)
 * - IS_PENDING = 0 (Android 10+、書き込み中の中間 state を除外)
 */
class MediaStoreScreenshotObserver(
    private val context: Context,
    handler: Handler,
    private val spec: WatchSpec,
    private val onNewScreenshot: (id: Long, uri: Uri, displayName: String) -> Unit,
) : ContentObserver(handler) {

    @Volatile
    private var lastSeenId: Long = -1L

    fun start() {
        if (!spec.anyEnabled) {
            DiagnosticsLog.warn(TAG, "no watch target enabled — observer not registered")
            return
        }
        lastSeenId = currentMaxId()
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            this,
        )
        val targets = listOfNotNull(
            "Screenshots".takeIf { spec.includeScreenshots },
            "DCIM/Camera".takeIf { spec.includeCameraPhotos },
        ).joinToString(", ")
        DiagnosticsLog.info(TAG, "registered, lastSeenId=$lastSeenId targets=[$targets]")
    }

    fun stop() {
        try {
            context.contentResolver.unregisterContentObserver(this)
        } catch (_: Exception) { /* idempotent */ }
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

    private fun buildPathSelection(): Pair<String, List<String>>? {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (spec.includeScreenshots) {
            clauses += "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            args += "%Screenshots/%"
        }
        if (spec.includeCameraPhotos) {
            // RELATIVE_PATH OR BUCKET_DISPLAY_NAME 二段構え (端末互換)
            clauses += "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?)"
            args += "%DCIM/Camera/%"
            args += "Camera"
        }
        if (clauses.isEmpty()) return null
        return "(${clauses.joinToString(" OR ")})" to args
    }

    private fun mimeWhitelistClause(): Pair<String, List<String>> {
        val mimes = listOf("image/jpeg", "image/jpg", "image/png", "image/heic", "image/heif", "image/webp", "image/gif")
        val placeholders = mimes.joinToString(",") { "?" }
        return "${MediaStore.Images.Media.MIME_TYPE} IN ($placeholders)" to mimes
    }

    private fun pendingClause(): String =
        // Android 10 (Q) 以降 IS_PENDING がある。書き込み中 (=1) を除外。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.IS_PENDING} = 0"
        } else {
            "1=1"
        }

    private fun currentMaxId(): Long {
        val pathSel = buildPathSelection() ?: return -1L
        val (mimeSel, mimeArgs) = mimeWhitelistClause()
        val sel = "${pathSel.first} AND $mimeSel AND ${pendingClause()}"
        val args = (pathSel.second + mimeArgs).toTypedArray()
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            sel,
            args,
            "${MediaStore.Images.Media._ID} DESC",
        )?.use { c ->
            if (c.moveToFirst()) c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            else -1L
        } ?: -1L
    }

    private fun scanForNew() {
        if (lastSeenId < 0) {
            // start() 前 / spec disabled で onChange 来た場合は init だけ
            lastSeenId = currentMaxId()
            return
        }
        val pathSel = buildPathSelection() ?: return
        val (mimeSel, mimeArgs) = mimeWhitelistClause()
        val sel = "${pathSel.first} AND $mimeSel AND ${pendingClause()} AND ${MediaStore.Images.Media._ID} > ?"
        val args = (pathSel.second + mimeArgs + listOf(lastSeenId.toString())).toTypedArray()
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        val newOnes = mutableListOf<Triple<Long, Uri, String>>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            proj,
            sel,
            args,
            "${MediaStore.Images.Media._ID} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: "image-$id.jpg"
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id,
                )
                newOnes.add(Triple(id, uri, name))
            }
        }
        if (newOnes.isEmpty()) {
            DiagnosticsLog.info(TAG, "scan: no new since id=$lastSeenId")
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

package jp.gmail.yamaga101.shotsync.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import jp.gmail.yamaga101.shotsync.SourceType

/**
 * MediaStore.Images に対する scan ロジック。旧 MediaStoreScreenshotObserver が
 * 内蔵していた SQL を抽出した object。CatchUpWorker と起動時 baseline 計算が共有して使う。
 *
 * filter:
 *  - RELATIVE_PATH または BUCKET_DISPLAY_NAME で `Screenshots/` または `DCIM/Camera/` を識別
 *  - MIME_TYPE は image/jpeg|jpg|png|heic|heif|webp|gif (RAW 除外)
 *  - IS_PENDING = 0 (Android 10+、書込み中除外)
 */
object MediaScanner {

    private val MIME_WHITELIST = listOf(
        "image/jpeg", "image/jpg", "image/png", "image/heic", "image/heif", "image/webp", "image/gif",
    )

    data class FoundImage(
        val id: Long,
        val uri: Uri,
        val name: String,
        val source: SourceType,
    )

    /** 現時点 MediaStore 上の最大 _ID。catch-up cursor の baseline にも使う。 */
    fun currentMaxId(context: Context, spec: WatchSpec): Long {
        val pathSel = buildPathSelection(spec) ?: return -1L
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

    /** `_ID > sinceId` の image を _ID ASC で返す。 */
    fun queryAfter(context: Context, spec: WatchSpec, sinceId: Long): List<FoundImage> {
        val pathSel = buildPathSelection(spec) ?: return emptyList()
        val (mimeSel, mimeArgs) = mimeWhitelistClause()
        val sel = "${pathSel.first} AND $mimeSel AND ${pendingClause()} AND ${MediaStore.Images.Media._ID} > ?"
        val args = (pathSel.second + mimeArgs + listOf(sinceId.toString())).toTypedArray()
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val out = mutableListOf<FoundImage>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            proj,
            sel,
            args,
            "${MediaStore.Images.Media._ID} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: "image-$id.jpg"
                val relPath = c.getString(pathCol) ?: ""
                val bucket = c.getString(bucketCol) ?: ""
                val source = classify(relPath, bucket)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id,
                )
                out.add(FoundImage(id, uri, name, source))
            }
        }
        return out
    }

    private fun buildPathSelection(spec: WatchSpec): Pair<String, List<String>>? {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (spec.includeScreenshots) {
            clauses += "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            args += "%Screenshots/%"
        }
        if (spec.includeCameraPhotos) {
            clauses += "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?)"
            args += "%DCIM/Camera/%"
            args += "Camera"
        }
        if (clauses.isEmpty()) return null
        return "(${clauses.joinToString(" OR ")})" to args
    }

    private fun mimeWhitelistClause(): Pair<String, List<String>> {
        val placeholders = MIME_WHITELIST.joinToString(",") { "?" }
        return "${MediaStore.Images.Media.MIME_TYPE} IN ($placeholders)" to MIME_WHITELIST
    }

    private fun pendingClause(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.IS_PENDING} = 0"
        } else {
            "1=1"
        }

    private fun classify(relativePath: String, bucket: String): SourceType {
        val isCamera = relativePath.contains("DCIM/Camera/", ignoreCase = true) ||
            bucket.equals("Camera", ignoreCase = true)
        return if (isCamera) SourceType.CAMERA else SourceType.SCREENSHOT
    }
}

/**
 * scan 対象の宣言。Settings 由来の boolean 2 個から構築する。
 */
data class WatchSpec(
    val includeScreenshots: Boolean,
    val includeCameraPhotos: Boolean,
) {
    val anyEnabled: Boolean get() = includeScreenshots || includeCameraPhotos
}

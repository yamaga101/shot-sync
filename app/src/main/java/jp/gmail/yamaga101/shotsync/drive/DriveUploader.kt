package jp.gmail.yamaga101.shotsync.drive

import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import java.io.File
import java.net.URLConnection

object DriveUploader {

    /**
     * `localFile` を `folderId` に新規アップロード。
     * `displayName` が指定されればそれを Drive 上の名前として使う (cache 用 prefix 等を
     * 削除するため)。指定なしなら localFile.name をそのまま使う。
     * 同名ファイルがあっても新規作成 (Drive は同名複数許容)。
     * 戻り値は Drive 側 file id。
     */
    fun uploadFile(drive: Drive, localFile: File, folderId: String, displayName: String? = null): String {
        val name = displayName ?: localFile.name
        val mimeType = guessMime(name)
        val metadata = DriveFile().apply {
            this.name = name
            parents = listOf(folderId)
        }
        val content = FileContent(mimeType, localFile)
        val created = drive.files().create(metadata, content)
            .setFields("id, name")
            .execute()
        return created.id
    }

    private fun guessMime(name: String): String =
        URLConnection.guessContentTypeFromName(name)
            ?: when {
                name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
                name.endsWith(".png", true) -> "image/png"
                name.endsWith(".webp", true) -> "image/webp"
                else -> "application/octet-stream"
            }
}

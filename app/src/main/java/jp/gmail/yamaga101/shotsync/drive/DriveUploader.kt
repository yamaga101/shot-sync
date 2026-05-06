package jp.gmail.yamaga101.shotsync.drive

import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import java.io.File
import java.net.URLConnection

object DriveUploader {

    /**
     * `localFile` を `folderId` に新規アップロード。同名ファイルがあっても新規作成。
     * Drive 側で重複しても OK (Drive は同名複数許容)。
     * 戻り値は Drive 側 file id。
     */
    fun uploadFile(drive: Drive, localFile: File, folderId: String): String {
        val mimeType = guessMime(localFile.name)
        val metadata = DriveFile().apply {
            name = localFile.name
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

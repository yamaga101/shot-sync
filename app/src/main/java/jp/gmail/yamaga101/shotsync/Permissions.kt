package jp.gmail.yamaga101.shotsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

/**
 * shot-sync が動作するために必要な OS-level 設定状態をまとめてチェックする。
 *
 * UI 側 (PermissionChecklistCard) はここの flags が全部 true になるまで案内を
 * 出し続ける。
 */
data class PermissionStatus(
    val notifications: Boolean,
    val media: Boolean,
    val ignoreBatteryOpt: Boolean,
) {
    val allGreen: Boolean
        get() = notifications && media && ignoreBatteryOpt
}

object Permissions {

    fun check(context: Context): PermissionStatus = PermissionStatus(
        notifications = isGranted(context, notificationPerm()),
        media = isGranted(context, mediaPerm()),
        ignoreBatteryOpt = isIgnoringBatteryOptimizations(context),
    )

    fun notificationPerm(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else null

    fun mediaPerm(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun isGranted(context: Context, perm: String?): Boolean {
        if (perm == null) return true  // SDK が古くて対象なし = OK
        return ContextCompat.checkSelfPermission(context, perm) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService<PowerManager>() ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** バッテリー最適化除外を request する system dialog を出す intent。 */
    fun requestIgnoreBatteryOptimizationsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Samsung 自動バッテリーマネジメント (制限あり/最適化/制限なし) を変えるため
     * アプリ詳細 → バッテリーセクションへ deep link。一般的に通用する fallback。 */
    fun appDetailsSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

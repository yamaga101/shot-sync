package jp.gmail.yamaga101.shotsync

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagEntry(
    val ts: Long,
    val severity: Severity,
    val tag: String,
    val message: String,
) {
    val timeLabel: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))
}

enum class Severity { INFO, WARN, ERROR }

/**
 * 全 Service / Observer / Worker から書き込む in-memory 診断ログ。
 * UI が StateFlow で購読して直近 N 件を表示する。
 *
 * プロセス再起動で消えるが、live debug 目的なので問題なし。
 * 将来必要なら DataStore にも mirror する。
 */
object DiagnosticsLog {
    private const val MAX_ENTRIES = 50
    private val _entries = MutableStateFlow<List<DiagEntry>>(emptyList())
    val entries: StateFlow<List<DiagEntry>> = _entries.asStateFlow()

    fun info(tag: String, message: String) = add(Severity.INFO, tag, message)
    fun warn(tag: String, message: String) = add(Severity.WARN, tag, message)
    fun error(tag: String, message: String) = add(Severity.ERROR, tag, message)

    private fun add(severity: Severity, tag: String, message: String) {
        val entry = DiagEntry(System.currentTimeMillis(), severity, tag, message)
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        when (severity) {
            Severity.ERROR -> Log.e("ShotSyncDiag/$tag", message)
            Severity.WARN -> Log.w("ShotSyncDiag/$tag", message)
            Severity.INFO -> Log.i("ShotSyncDiag/$tag", message)
        }
    }
}

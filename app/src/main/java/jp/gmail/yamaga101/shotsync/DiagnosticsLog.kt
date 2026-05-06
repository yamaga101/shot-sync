package jp.gmail.yamaga101.shotsync

import android.content.Context
import android.util.Log
import jp.gmail.yamaga101.shotsync.data.AppDatabase
import jp.gmail.yamaga101.shotsync.data.DiagEntryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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
 * 全 Service / Worker / Job から書き込む診断ログ。2 段構成:
 *
 * 1. **hot in-memory facade** (50 件 ring buffer) — UI が StateFlow で観察。書込み即座反映、tearing free
 * 2. **Room async mirror** (rolling 500 件 / 7 日) — process 再起動後も復元可能
 *
 * Application#onCreate で `attach(context)` を 1 度呼ぶ前提。未 attach の状態で
 * call されても in-memory のみ書き込まれて落ちない (defensive)。
 */
object DiagnosticsLog {
    private const val MAX_HOT_ENTRIES = 50
    private const val MAX_PERSISTED_ENTRIES = 500
    private val PERSISTED_TTL_MILLIS = TimeUnit.DAYS.toMillis(7)

    private val _entries = MutableStateFlow<List<DiagEntry>>(emptyList())
    val entries: StateFlow<List<DiagEntry>> = _entries.asStateFlow()

    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Application#onCreate から 1 度呼ぶ。Room の lazy init を発火させ、過去ログを hot に load。 */
    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            try {
                val dao = AppDatabase.get(context).diagDao()
                // 過去ログの最初の snapshot を hot に流す。以降は append-only mirror。
                dao.observeRecent(MAX_HOT_ENTRIES)
                    .map { rows -> rows.map(DiagEntryEntity::toDomain) }
                    .collect { snapshot ->
                        // hot は新しい順、Room も DESC で揃える
                        _entries.value = snapshot
                    }
            } catch (e: Throwable) {
                Log.e("ShotSyncDiag/attach", "Room observe failed: ${e.message}")
            }
        }
    }

    fun info(tag: String, message: String) = add(Severity.INFO, tag, message)
    fun warn(tag: String, message: String) = add(Severity.WARN, tag, message)
    fun error(tag: String, message: String) = add(Severity.ERROR, tag, message)

    private fun add(severity: Severity, tag: String, message: String) {
        val entry = DiagEntry(System.currentTimeMillis(), severity, tag, message)
        // hot 即時反映 (UI が attach 前でも動く)
        _entries.value = (listOf(entry) + _entries.value).take(MAX_HOT_ENTRIES)
        when (severity) {
            Severity.ERROR -> Log.e("ShotSyncDiag/$tag", message)
            Severity.WARN -> Log.w("ShotSyncDiag/$tag", message)
            Severity.INFO -> Log.i("ShotSyncDiag/$tag", message)
        }
        // Room へ async mirror
        val ctx = appContext ?: return
        scope.launch {
            try {
                val dao = AppDatabase.get(ctx).diagDao()
                val cutoff = System.currentTimeMillis() - PERSISTED_TTL_MILLIS
                dao.insertAndTrim(DiagEntryEntity.fromDomain(entry), MAX_PERSISTED_ENTRIES, cutoff)
            } catch (e: Throwable) {
                Log.e("ShotSyncDiag/persist", "insert failed: ${e.message}")
            }
        }
    }
}

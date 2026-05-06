package jp.gmail.yamaga101.shotsync.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import jp.gmail.yamaga101.shotsync.DiagEntry
import jp.gmail.yamaga101.shotsync.Severity

@Entity(tableName = "diag_log")
data class DiagEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val severityName: String,
    val tag: String,
    val message: String,
) {
    fun toDomain(): DiagEntry = DiagEntry(
        ts = ts,
        severity = runCatching { Severity.valueOf(severityName) }.getOrDefault(Severity.INFO),
        tag = tag,
        message = message,
    )

    companion object {
        fun fromDomain(e: DiagEntry): DiagEntryEntity = DiagEntryEntity(
            ts = e.ts,
            severityName = e.severity.name,
            tag = e.tag,
            message = e.message,
        )
    }
}

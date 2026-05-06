package jp.gmail.yamaga101.shotsync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagDao {
    @Insert
    suspend fun insert(entry: DiagEntryEntity)

    @Query("SELECT * FROM diag_log ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DiagEntryEntity>>

    @Query("DELETE FROM diag_log WHERE id NOT IN (SELECT id FROM diag_log ORDER BY ts DESC LIMIT :limit)")
    suspend fun trimByCount(limit: Int)

    @Query("DELETE FROM diag_log WHERE ts < :cutoffMillis")
    suspend fun trimByAge(cutoffMillis: Long)

    @Transaction
    suspend fun insertAndTrim(entry: DiagEntryEntity, maxRows: Int, ttlCutoffMillis: Long) {
        insert(entry)
        trimByCount(maxRows)
        trimByAge(ttlCutoffMillis)
    }
}

package com.example.paperclipper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingUsageReportDao {
    /** IGNORE: a report is queued at most once, keyed by its client-generated reportId. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PendingUsageReportEntity)

    /** Reports old enough to upload (createdAt <= cutoff), oldest first, capped at [limit]. */
    @Query(
        "SELECT * FROM pending_usage_reports WHERE createdAt <= :cutoff " +
            "ORDER BY createdAt ASC LIMIT :limit",
    )
    suspend fun eligible(cutoff: Long, limit: Int): List<PendingUsageReportEntity>

    /** createdAt of the oldest queued report (drives the upload delay), or null when empty. */
    @Query("SELECT MIN(createdAt) FROM pending_usage_reports")
    suspend fun oldestCreatedAt(): Long?

    @Query("DELETE FROM pending_usage_reports WHERE reportId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM pending_usage_reports")
    suspend fun count(): Int
}

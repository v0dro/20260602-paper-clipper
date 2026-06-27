package com.example.paperclipper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClippingDao {
    @Query("SELECT * FROM clippings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ClippingEntity>>

    @Query("SELECT * FROM clippings ORDER BY createdAt DESC")
    suspend fun getAll(): List<ClippingEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: ClippingEntity)

    @Query("SELECT fileName FROM clippings")
    suspend fun allFileNames(): List<String>

    @Query("SELECT * FROM clippings WHERE status = :status ORDER BY createdAt ASC")
    suspend fun withStatus(status: String): List<ClippingEntity>

    @Query("UPDATE clippings SET status = :status WHERE fileName = :fileName")
    suspend fun updateStatus(fileName: String, status: String)

    @Query(
        "UPDATE clippings SET status = :status, extractedText = :extractedText, " +
            "summary = :summary, heading = :heading, errorMessage = :errorMessage, " +
            "model = :model, processedAt = :processedAt WHERE fileName = :fileName",
    )
    suspend fun updateResult(
        fileName: String,
        status: String,
        extractedText: String?,
        summary: String?,
        heading: String?,
        errorMessage: String?,
        model: String?,
        processedAt: Long?,
    )

    @Query("DELETE FROM clippings WHERE fileName IN (:fileNames)")
    suspend fun deleteByNames(fileNames: List<String>)

    @Query("DELETE FROM clippings")
    suspend fun deleteAll()
}

package com.example.paperclipper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CommentDao {
    @Query("SELECT * FROM comments WHERE fileName = :fileName ORDER BY createdAt DESC")
    fun observeFor(fileName: String): Flow<List<CommentEntity>>

    @Query("SELECT * FROM comments WHERE fileName = :fileName ORDER BY createdAt ASC")
    suspend fun commentsForOnce(fileName: String): List<CommentEntity>

    @Insert
    suspend fun insert(comment: CommentEntity)

    @Query("DELETE FROM comments WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM comments WHERE fileName IN (:fileNames)")
    suspend fun deleteForFiles(fileNames: List<String>)

    @Query("DELETE FROM comments")
    suspend fun deleteAll()
}

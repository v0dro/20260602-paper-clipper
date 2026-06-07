package com.example.paperclipper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE name COLLATE NOCASE = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Query(
        "SELECT t.* FROM tags t INNER JOIN clipping_tags ct ON t.id = ct.tagId " +
            "WHERE ct.fileName = :fileName ORDER BY t.name COLLATE NOCASE ASC",
    )
    fun observeTagsFor(fileName: String): Flow<List<TagEntity>>

    @Query(
        "SELECT t.* FROM tags t INNER JOIN clipping_tags ct ON t.id = ct.tagId " +
            "WHERE ct.fileName = :fileName ORDER BY t.name COLLATE NOCASE ASC",
    )
    suspend fun tagsForOnce(fileName: String): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assign(ref: ClippingTagCrossRef)

    @Query("DELETE FROM clipping_tags WHERE fileName = :fileName AND tagId = :tagId")
    suspend fun unassign(fileName: String, tagId: Long)

    @Query("DELETE FROM clipping_tags WHERE fileName IN (:fileNames)")
    suspend fun deleteRefsForFiles(fileNames: List<String>)

    @Query("DELETE FROM clipping_tags")
    suspend fun deleteAllRefs()

    @Query("DELETE FROM tags")
    suspend fun deleteAllTags()
}

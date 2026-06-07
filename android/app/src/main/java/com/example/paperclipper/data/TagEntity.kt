package com.example.paperclipper.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A globally-reusable tag. Unique by name; usable from any clipping once created. */
@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

/** Many-to-many link between a clipping (by file name) and a global tag. */
@Entity(tableName = "clipping_tags", primaryKeys = ["fileName", "tagId"], indices = [Index("tagId")])
data class ClippingTagCrossRef(
    val fileName: String,
    val tagId: Long,
)

/** A free-text comment attached to a single clipping. */
@Entity(tableName = "comments", indices = [Index("fileName")])
data class CommentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val text: String,
    val createdAt: Long,
)

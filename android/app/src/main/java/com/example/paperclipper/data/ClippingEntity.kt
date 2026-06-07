package com.example.paperclipper.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Processing state of a clipping's Gemini analysis. */
enum class ClippingStatus { PENDING, PROCESSING, SUCCESS, ERROR }

/**
 * One row per saved clipping image. The image bytes live on disk in the clippings dir; this table
 * holds the Gemini-derived text/summary and processing metadata, keyed by the image file name.
 */
@Entity(tableName = "clippings")
data class ClippingEntity(
    @PrimaryKey val fileName: String,
    val createdAt: Long,
    val status: String = ClippingStatus.PENDING.name,
    val extractedText: String? = null,
    val summary: String? = null,
    val errorMessage: String? = null,
    val model: String? = null,
    val processedAt: Long? = null,
)

package com.example.paperclipper.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One queued usage report awaiting its deferred upload to the home server's `POST /report-usage`
 * (see [com.example.paperclipper.gemini.UsageReport]). Rows are created whenever the Cloudflare
 * Worker fallback served an /analyze call and deleted once the server acknowledges the report.
 *
 * The report itself is stored as its finished wire JSON in [payloadJson], so the item shape can
 * evolve without further Room migrations; [createdAt] (epoch ms) drives the ≥24 h upload delay.
 */
@Entity(tableName = "pending_usage_reports")
data class PendingUsageReportEntity(
    @PrimaryKey val reportId: String,
    val createdAt: Long,
    val payloadJson: String,
)

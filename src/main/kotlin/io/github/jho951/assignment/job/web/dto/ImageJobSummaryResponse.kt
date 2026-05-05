package io.github.jho951.assignment.job.web.dto

import java.time.Instant
import kotlin.jvm.JvmRecord

@JvmRecord
data class ImageJobSummaryResponse(
    val jobId: String,
    val status: String,
    val imageUrl: String,
    val attemptCount: Int,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val completedAt: Instant?,
    val expiresAt: Instant?,
    val error: ImageJobErrorResponse?
)

package io.github.jho951.assignment.job.web.dto

import java.time.Instant
import kotlin.jvm.JvmRecord

@JvmRecord
data class ImageJobResultResponse(
    val jobId: String,
    val status: String,
    val result: String?,
    val completedAt: Instant?,
    val expiresAt: Instant?,
    val error: ImageJobErrorResponse?
)

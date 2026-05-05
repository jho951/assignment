package io.github.jho951.assignment.job.web.dto

import kotlin.jvm.JvmRecord

@JvmRecord
data class ImageJobListResponse(
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val items: List<ImageJobSummaryResponse>
)

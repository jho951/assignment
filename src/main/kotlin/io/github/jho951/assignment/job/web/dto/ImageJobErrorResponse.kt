package io.github.jho951.assignment.job.web.dto

import kotlin.jvm.JvmRecord

@JvmRecord
data class ImageJobErrorResponse(
    val code: String?,
    val message: String?
)

package io.github.jho951.assignment.job.web.dto

import kotlin.jvm.JvmRecord

@JvmRecord
data class ApiErrorResponse(
    val code: String,
    val message: String
)

package io.github.jho951.assignment.job.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import kotlin.jvm.JvmRecord

@JvmRecord
data class ImageJobCreateRequest(
    @field:NotBlank(message = "imageUrl is required")
    @field:Pattern(regexp = "^https?://.+$", message = "imageUrl must be a valid http/https URL")
    val imageUrl: String?
)

package io.github.jho951.assignment.job.web

import io.github.jho951.assignment.job.domain.JobStatus
import io.github.jho951.assignment.job.service.ImageJobCommandService
import io.github.jho951.assignment.job.service.ImageJobQueryService
import io.github.jho951.assignment.job.web.dto.ImageJobCreateRequest
import io.github.jho951.assignment.job.web.dto.ImageJobCreateResponse
import io.github.jho951.assignment.job.web.dto.ImageJobListResponse
import io.github.jho951.assignment.job.web.dto.ImageJobResultResponse
import io.github.jho951.assignment.job.web.dto.ImageJobStatusResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/image-jobs")
class ImageJobController(
    private val imageJobQueryService: ImageJobQueryService,
    private val imageJobCommandService: ImageJobCommandService
) {

    @PostMapping
    fun createJob(
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: ImageJobCreateRequest
    ): ResponseEntity<ImageJobCreateResponse> {
        val result = imageJobCommandService.createJob(idempotencyKey, request)
        val response = ImageJobCreateResponse(
            result.imageJob.jobId,
            result.imageJob.status.name,
            result.imageJob.createdAt ?: error("createdAt must be populated after persistence")
        )
        return if (result.replayed) {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.accepted().body(response)
        }
    }

    @GetMapping("/{jobId}")
    fun getJobStatus(@PathVariable jobId: String): ImageJobStatusResponse =
        imageJobQueryService.getJobStatus(jobId)

    @GetMapping("/{jobId}/result")
    fun getJobResult(@PathVariable jobId: String): ImageJobResultResponse =
        imageJobQueryService.getJobResult(jobId)

    @GetMapping
    fun listJobs(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) status: JobStatus?
    ): ImageJobListResponse = imageJobQueryService.listJobs(page, size, status)
}

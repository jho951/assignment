package io.github.jho951.assignment.job.service

import io.github.jho951.assignment.job.domain.ImageJob
import io.github.jho951.assignment.job.domain.JobFailureCode
import io.github.jho951.assignment.job.repository.ImageJobRepository
import io.github.jho951.assignment.job.web.ApiException
import io.github.jho951.assignment.job.web.dto.ImageJobCreateRequest
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.jvm.JvmRecord
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class ImageJobCommandService(
    private val imageJobRepository: ImageJobRepository,
    private val requestHashService: RequestHashService,
    private val clock: Clock
) {

    fun createJob(idempotencyKey: String?, request: ImageJobCreateRequest): CreateJobResult {
        if (idempotencyKey == null) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                JobFailureCode.MISSING_IDEMPOTENCY_KEY,
                "Idempotency-Key header is required"
            )
        }

        val normalizedKey = idempotencyKey.trim()
        validateIdempotencyKey(normalizedKey)
        val imageUrl = request.imageUrl ?: error("imageUrl must be validated before command execution")
        val requestHash = requestHashService.hashImageUrl(imageUrl)

        val existingJob = imageJobRepository.findByIdempotencyKey(normalizedKey)
        if (existingJob.isPresent) {
            val imageJob = existingJob.get()
            if (imageJob.requestHash != requestHash) {
                throw ApiException(
                    HttpStatus.CONFLICT,
                    JobFailureCode.IDEMPOTENCY_KEY_CONFLICT,
                    "The same Idempotency-Key was used with a different request body"
                )
            }
            return CreateJobResult(imageJob, true)
        }

        val now = Instant.now(clock)
        val newJob = ImageJob.queued(
            generateJobId(),
            normalizedKey,
            requestHash,
            imageUrl,
            now
        )

        try {
            return CreateJobResult(imageJobRepository.save(newJob), false)
        } catch (exception: DataIntegrityViolationException) {
            val imageJob = imageJobRepository.findByIdempotencyKey(normalizedKey)
                .orElseThrow { exception }
            if (imageJob.requestHash != requestHash) {
                throw ApiException(
                    HttpStatus.CONFLICT,
                    JobFailureCode.IDEMPOTENCY_KEY_CONFLICT,
                    "The same Idempotency-Key was used with a different request body"
                )
            }
            return CreateJobResult(imageJob, true)
        }
    }

    private fun generateJobId(): String = "job_${UUID.randomUUID().toString().replace("-", "")}"

    private fun validateIdempotencyKey(idempotencyKey: String) {
        if (idempotencyKey.isEmpty()
            || idempotencyKey.length > IDEMPOTENCY_KEY_MAX_LENGTH
            || !idempotencyKey.matches(IDEMPOTENCY_KEY_PATTERN.toRegex())
        ) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                JobFailureCode.INVALID_IDEMPOTENCY_KEY,
                INVALID_IDEMPOTENCY_KEY_MESSAGE
            )
        }
    }

    @JvmRecord
    data class CreateJobResult(
        val imageJob: ImageJob,
        val replayed: Boolean
    )

    private companion object {
        const val IDEMPOTENCY_KEY_MAX_LENGTH = 128
        const val IDEMPOTENCY_KEY_PATTERN = "^[A-Za-z0-9._-]+$"
        const val INVALID_IDEMPOTENCY_KEY_MESSAGE =
            "Idempotency-Key must be 1-128 characters of letters, digits, dot, underscore, or hyphen"
    }
}

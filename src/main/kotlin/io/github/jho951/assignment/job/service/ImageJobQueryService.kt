package io.github.jho951.assignment.job.service

import io.github.jho951.assignment.job.domain.ImageJob
import io.github.jho951.assignment.job.domain.JobFailureCode
import io.github.jho951.assignment.job.domain.JobStatus
import io.github.jho951.assignment.job.repository.ImageJobRepository
import io.github.jho951.assignment.job.web.ApiException
import io.github.jho951.assignment.job.web.dto.ImageJobErrorResponse
import io.github.jho951.assignment.job.web.dto.ImageJobListResponse
import io.github.jho951.assignment.job.web.dto.ImageJobResultResponse
import io.github.jho951.assignment.job.web.dto.ImageJobStatusResponse
import io.github.jho951.assignment.job.web.dto.ImageJobSummaryResponse
import java.time.Clock
import java.time.Instant
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ImageJobQueryService(
    private val imageJobRepository: ImageJobRepository,
    private val clock: Clock
) {

    fun getJobStatus(jobId: String): ImageJobStatusResponse {
        val imageJob = findJob(jobId, Instant.now(clock))
        return ImageJobStatusResponse(
            imageJob.jobId,
            imageJob.status.name,
            imageJob.imageUrl,
            imageJob.attemptCount,
            imageJob.createdAt,
            imageJob.updatedAt,
            imageJob.completedAt,
            imageJob.expiresAt,
            mapError(imageJob)
        )
    }

    fun getJobResult(jobId: String): ImageJobResultResponse {
        val imageJob = findJob(jobId, Instant.now(clock))
        if (!imageJob.status.isTerminal()) {
            throw ApiException(
                HttpStatus.CONFLICT,
                JobFailureCode.RESULT_NOT_READY,
                "The result is not ready yet"
            )
        }
        return ImageJobResultResponse(
            imageJob.jobId,
            imageJob.status.name,
            imageJob.result,
            imageJob.completedAt,
            imageJob.expiresAt,
            mapError(imageJob)
        )
    }

    fun listJobs(page: Int, size: Int, status: JobStatus?): ImageJobListResponse {
        val normalizedPage = maxOf(page, 0)
        val normalizedSize = minOf(maxOf(size, 1), 100)
        val pageable: Pageable = PageRequest.of(normalizedPage, normalizedSize, DEFAULT_SORT)
        val now = Instant.now(clock)

        val imageJobs = if (status == null) {
            imageJobRepository.findVisibleJobs(TERMINAL_STATUSES, now, pageable)
        } else {
            imageJobRepository.findVisibleJobsByStatus(status, TERMINAL_STATUSES, now, pageable)
        }

        val items = imageJobs.content.map { job ->
            ImageJobSummaryResponse(
                job.jobId,
                job.status.name,
                job.imageUrl,
                job.attemptCount,
                job.createdAt,
                job.updatedAt,
                job.completedAt,
                job.expiresAt,
                mapError(job)
            )
        }

        return ImageJobListResponse(
            imageJobs.number,
            imageJobs.size,
            imageJobs.totalElements,
            imageJobs.totalPages,
            items
        )
    }

    private fun findJob(jobId: String, now: Instant): ImageJob {
        val imageJob = imageJobRepository.findByJobId(jobId)
            .orElseThrow { notFound() }
        if (isExpired(imageJob, now)) {
            throw notFound()
        }
        return imageJob
    }

    private fun isExpired(imageJob: ImageJob, now: Instant): Boolean =
        imageJob.isTerminal() && imageJob.expiresAt?.isAfter(now) == false

    private fun notFound(): ApiException =
        ApiException(
            HttpStatus.NOT_FOUND,
            JobFailureCode.JOB_NOT_FOUND,
            "Image job not found"
        )

    private fun mapError(imageJob: ImageJob): ImageJobErrorResponse? =
        imageJob.errorCode?.let { ImageJobErrorResponse(it, imageJob.errorMessage) }

    private companion object {
        val TERMINAL_STATUSES = listOf(JobStatus.SUCCEEDED, JobStatus.FAILED)
        val DEFAULT_SORT: Sort = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("jobId")
        )
    }
}

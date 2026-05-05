package io.github.jho951.assignment.job.service

import io.github.jho951.assignment.job.domain.ImageJob
import io.github.jho951.assignment.job.domain.JobFailureCode
import io.github.jho951.assignment.job.domain.JobStatus
import io.github.jho951.assignment.job.repository.ImageJobRepository
import io.github.jho951.assignment.job.web.ApiException
import io.github.jho951.assignment.job.web.dto.ImageJobListResponse
import io.github.jho951.assignment.job.web.dto.ImageJobResultResponse
import io.github.jho951.assignment.job.web.dto.ImageJobStatusResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus

@ExtendWith(MockitoExtension::class)
class ImageJobQueryServiceTests {

    private val clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

    @Mock
    private lateinit var imageJobRepository: ImageJobRepository

    private lateinit var imageJobQueryService: ImageJobQueryService

    @BeforeEach
    fun setUp() {
        imageJobQueryService = ImageJobQueryService(imageJobRepository, clock)
    }

    @Test
    fun shouldReturnJobStatusWithMappedError() {
        val imageJob = job("job-1", JobStatus.FAILED)
        imageJob.attemptCount = 2
        imageJob.errorCode = JobFailureCode.WORKER_TIMEOUT.name
        imageJob.errorMessage = "timed out"
        whenever(imageJobRepository.findByJobId("job-1")).thenReturn(Optional.of(imageJob))

        val response: ImageJobStatusResponse = imageJobQueryService.getJobStatus("job-1")

        assertThat(response.jobId).isEqualTo("job-1")
        assertThat(response.status).isEqualTo("FAILED")
        assertThat(response.imageUrl).isEqualTo("https://example.com/job-1.png")
        assertThat(response.attemptCount).isEqualTo(2)
        assertThat(response.error).isNotNull()
        assertThat(response.error!!.code).isEqualTo(JobFailureCode.WORKER_TIMEOUT.name)
        assertThat(response.error!!.message).isEqualTo("timed out")
    }

    @Test
    fun shouldThrowNotFoundWhenJobStatusIsMissing() {
        whenever(imageJobRepository.findByJobId("missing")).thenReturn(Optional.empty())

        assertThatThrownBy { imageJobQueryService.getJobStatus("missing") }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertApiException(
                    it,
                    HttpStatus.NOT_FOUND,
                    JobFailureCode.JOB_NOT_FOUND
                )
            }
    }

    @Test
    fun shouldThrowNotFoundWhenJobStatusIsExpired() {
        val imageJob = job("expired-job", JobStatus.SUCCEEDED)
        imageJob.completedAt = NOW.minusSeconds(60)
        imageJob.expiresAt = NOW
        whenever(imageJobRepository.findByJobId("expired-job")).thenReturn(Optional.of(imageJob))

        assertThatThrownBy { imageJobQueryService.getJobStatus("expired-job") }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertApiException(
                    it,
                    HttpStatus.NOT_FOUND,
                    JobFailureCode.JOB_NOT_FOUND
                )
            }
    }

    @Test
    fun shouldThrowConflictWhenResultIsNotReady() {
        val imageJob = job("job-2", JobStatus.PROCESSING)
        whenever(imageJobRepository.findByJobId("job-2")).thenReturn(Optional.of(imageJob))

        assertThatThrownBy { imageJobQueryService.getJobResult("job-2") }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertApiException(
                    it,
                    HttpStatus.CONFLICT,
                    JobFailureCode.RESULT_NOT_READY
                )
            }
    }

    @Test
    fun shouldReturnTerminalJobResultWithMappedError() {
        val imageJob = job("job-3", JobStatus.FAILED)
        imageJob.result = null
        imageJob.completedAt = Instant.parse("2026-05-05T00:01:00Z")
        imageJob.expiresAt = Instant.parse("2026-05-12T00:01:00Z")
        imageJob.errorCode = JobFailureCode.WORKER_UNAVAILABLE.name
        imageJob.errorMessage = "worker unavailable"
        whenever(imageJobRepository.findByJobId("job-3")).thenReturn(Optional.of(imageJob))

        val response: ImageJobResultResponse = imageJobQueryService.getJobResult("job-3")

        assertThat(response.jobId).isEqualTo("job-3")
        assertThat(response.status).isEqualTo("FAILED")
        assertThat(response.error).isNotNull()
        assertThat(response.error!!.code).isEqualTo(JobFailureCode.WORKER_UNAVAILABLE.name)
        assertThat(response.completedAt).isEqualTo(Instant.parse("2026-05-05T00:01:00Z"))
        assertThat(response.expiresAt).isEqualTo(Instant.parse("2026-05-12T00:01:00Z"))
    }

    @Test
    fun shouldListJobsUsingNormalizedPaginationAndDefaultSort() {
        val imageJob = job("job-4", JobStatus.QUEUED)
        val pageable = PageRequest.of(0, 100, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("jobId")))
        val page: Page<ImageJob> = PageImpl(
            listOf(imageJob),
            pageable,
            1
        )
        whenever(
            imageJobRepository.findVisibleJobs(
                listOf(JobStatus.SUCCEEDED, JobStatus.FAILED),
                NOW,
                pageable
            )
        ).thenReturn(page)

        val response: ImageJobListResponse = imageJobQueryService.listJobs(-3, 500, null)

        assertThat(response.page).isZero()
        assertThat(response.size).isEqualTo(100)
        assertThat(response.totalElements).isEqualTo(1)
        assertThat(response.totalPages).isEqualTo(1)
        assertThat(response.items).hasSize(1)
        assertThat(response.items[0].jobId).isEqualTo("job-4")
        verify(imageJobRepository).findVisibleJobs(
            listOf(JobStatus.SUCCEEDED, JobStatus.FAILED),
            NOW,
            pageable
        )
    }

    @Test
    fun shouldListJobsByStatusFilter() {
        val imageJob = job("job-5", JobStatus.FAILED)
        imageJob.errorCode = JobFailureCode.INTERNAL_ERROR.name
        imageJob.errorMessage = "boom"
        val pageable = PageRequest.of(1, 5, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("jobId")))
        val page: Page<ImageJob> = PageImpl(listOf(imageJob), pageable, 6)
        whenever(
            imageJobRepository.findVisibleJobsByStatus(
                JobStatus.FAILED,
                listOf(JobStatus.SUCCEEDED, JobStatus.FAILED),
                NOW,
                pageable
            )
        ).thenReturn(page)

        val response = imageJobQueryService.listJobs(1, 5, JobStatus.FAILED)

        assertThat(response.page).isEqualTo(1)
        assertThat(response.size).isEqualTo(5)
        assertThat(response.totalElements).isEqualTo(6)
        assertThat(response.items).hasSize(1)
        assertThat(response.items[0].error).isNotNull()
        assertThat(response.items[0].error!!.code).isEqualTo(JobFailureCode.INTERNAL_ERROR.name)
        verify(imageJobRepository).findVisibleJobsByStatus(
            JobStatus.FAILED,
            listOf(JobStatus.SUCCEEDED, JobStatus.FAILED),
            NOW,
            pageable
        )
    }

    private fun assertApiException(exception: ApiException, httpStatus: HttpStatus, code: JobFailureCode) {
        assertThat(exception.httpStatus).isEqualTo(httpStatus)
        assertThat(exception.code).isEqualTo(code)
    }

    private fun job(jobId: String, status: JobStatus): ImageJob {
        val imageJob = ImageJob.queued(
            jobId,
            "idem-$jobId",
            "hash-$jobId",
            "https://example.com/$jobId.png",
            Instant.parse("2026-05-05T00:00:00Z")
        )
        imageJob.status = status
        return imageJob
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-05T00:00:30Z")
    }
}

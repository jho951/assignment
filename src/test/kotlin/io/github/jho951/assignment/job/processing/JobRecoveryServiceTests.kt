package io.github.jho951.assignment.job.processing

import io.github.jho951.assignment.config.JobProperties
import io.github.jho951.assignment.job.domain.ImageJob
import io.github.jho951.assignment.job.domain.JobFailureCode
import io.github.jho951.assignment.job.domain.JobStatus
import io.github.jho951.assignment.job.domain.JobStatusTransitionPolicy
import io.github.jho951.assignment.job.repository.ImageJobRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.PageRequest

@ExtendWith(MockitoExtension::class)
class JobRecoveryServiceTests {

    @Mock
    private lateinit var imageJobRepository: ImageJobRepository

    @Mock
    private lateinit var jobProcessor: JobProcessor

    private lateinit var jobRecoveryService: JobRecoveryService

    @BeforeEach
    fun setUp() {
        jobRecoveryService = JobRecoveryService(
            imageJobRepository,
            JobStatusTransitionPolicy(),
            jobProcessor,
            JobProperties(
                3,
                1_000,
                20,
                5_000,
                60_000,
                false,
                JobProperties.Executor(1, 1, 1)
            ),
            Clock.fixed(NOW, ZoneOffset.UTC)
        )
    }

    @Test
    fun shouldRescheduleStaleProcessingJobsWithinAttemptLimit() {
        val staleJob = processingJob("job-stale", 1)
        whenever(imageJobRepository.findStaleProcessingJobs(JobStatus.PROCESSING, NOW, PageRequest.of(0, 20)))
            .thenReturn(listOf(staleJob))
        whenever(jobProcessor.calculateBackoff(1)).thenReturn(Duration.ofSeconds(2))

        jobRecoveryService.recoverStaleJobs()

        assertThat(staleJob.status).isEqualTo(JobStatus.RETRY_SCHEDULED)
        assertThat(staleJob.errorCode).isEqualTo(JobFailureCode.WORKER_UNAVAILABLE.name)
        assertThat(staleJob.errorMessage).isEqualTo("Recovered stale processing job")
        assertThat(staleJob.leaseUntil).isNull()
        assertThat(staleJob.nextAttemptAt).isEqualTo(NOW.plusSeconds(2))

        verify(imageJobRepository).findStaleProcessingJobs(JobStatus.PROCESSING, NOW, PageRequest.of(0, 20))
    }

    @Test
    fun shouldFailStaleProcessingJobsWhenAttemptLimitIsExceeded() {
        val staleJob = processingJob("job-exhausted", 3)
        whenever(imageJobRepository.findStaleProcessingJobs(JobStatus.PROCESSING, NOW, PageRequest.of(0, 20)))
            .thenReturn(listOf(staleJob))

        jobRecoveryService.recoverStaleJobs()

        assertThat(staleJob.status).isEqualTo(JobStatus.FAILED)
        assertThat(staleJob.errorCode).isEqualTo(JobFailureCode.MAX_ATTEMPTS_EXCEEDED.name)
        assertThat(staleJob.errorMessage).isEqualTo("Maximum retry attempts exceeded during stale job recovery")
        assertThat(staleJob.leaseUntil).isNull()
        assertThat(staleJob.completedAt).isEqualTo(NOW)
        assertThat(staleJob.expiresAt).isEqualTo(NOW.plusSeconds(7 * 24 * 60 * 60L))
    }

    private fun processingJob(jobId: String, attemptCount: Int): ImageJob {
        val imageJob = ImageJob.queued(
            jobId,
            "idem-$jobId",
            "hash-$jobId",
            "https://example.com/$jobId.png",
            NOW.minusSeconds(10)
        )
        imageJob.status = JobStatus.PROCESSING
        imageJob.attemptCount = attemptCount
        imageJob.leaseUntil = NOW.minusSeconds(1)
        return imageJob
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-05T00:00:00Z")
    }
}

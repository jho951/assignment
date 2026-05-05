package io.github.jho951.assignment.job.processing

import io.github.jho951.assignment.config.JobProperties
import io.github.jho951.assignment.job.domain.ImageJob
import io.github.jho951.assignment.job.domain.JobStatus
import io.github.jho951.assignment.job.repository.ImageJobRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.springframework.data.domain.PageRequest
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class JobCleanupSchedulerTests {

    @Mock
    private lateinit var imageJobRepository: ImageJobRepository

    private lateinit var jobCleanupScheduler: JobCleanupScheduler

    @BeforeEach
    fun setUp() {
        jobCleanupScheduler = JobCleanupScheduler(
            imageJobRepository,
            JobProperties(
                3,
                1_000,
                15,
                5_000,
                60_000,
                false,
                JobProperties.Executor(1, 1, 1)
            ),
            Clock.fixed(NOW, ZoneOffset.UTC)
        )
    }

    @Test
    fun shouldDeleteExpiredTerminalJobsWhenFound() {
        val expiredSucceeded = ImageJob.queued(
            "job-expired",
            "idem-expired",
            "hash-expired",
            "https://example.com/expired.png",
            NOW.minusSeconds(10)
        )
        expiredSucceeded.status = JobStatus.SUCCEEDED
        whenever(
            imageJobRepository.findExpiredJobs(
                listOf(JobStatus.SUCCEEDED, JobStatus.FAILED),
                NOW,
                PageRequest.of(0, 15)
            )
        )
            .thenReturn(listOf(expiredSucceeded))

        jobCleanupScheduler.cleanupExpiredJobs()

        verify(imageJobRepository).findExpiredJobs(
            listOf(JobStatus.SUCCEEDED, JobStatus.FAILED),
            NOW,
            PageRequest.of(0, 15)
        )
        verify(imageJobRepository).deleteAllInBatch(listOf(expiredSucceeded))
    }

    @Test
    fun shouldSkipDeletionWhenNoExpiredJobsExist() {
        whenever(
            imageJobRepository.findExpiredJobs(
                listOf(JobStatus.SUCCEEDED, JobStatus.FAILED),
                NOW,
                PageRequest.of(0, 15)
            )
        )
            .thenReturn(listOf())

        jobCleanupScheduler.cleanupExpiredJobs()

        verify(imageJobRepository, never()).deleteAllInBatch(anyList<ImageJob>())
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-05T00:00:00Z")
    }
}

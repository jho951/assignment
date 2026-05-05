package io.github.jho951.assignment.job.domain

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ImageJobTests {

    @Test
    fun shouldCreateQueuedJobWithExpectedDefaults() {
        val nextAttemptAt = Instant.parse("2026-05-05T00:00:00Z")

        val imageJob = ImageJob.queued(
            "job-1",
            "idem-1",
            "hash-1",
            "https://example.com/image.png",
            nextAttemptAt
        )

        assertThat(imageJob.jobId).isEqualTo("job-1")
        assertThat(imageJob.idempotencyKey).isEqualTo("idem-1")
        assertThat(imageJob.requestHash).isEqualTo("hash-1")
        assertThat(imageJob.imageInputType).isEqualTo(ImageInputType.URL)
        assertThat(imageJob.imageUrl).isEqualTo("https://example.com/image.png")
        assertThat(imageJob.status).isEqualTo(JobStatus.QUEUED)
        assertThat(imageJob.nextAttemptAt).isEqualTo(nextAttemptAt)
        assertThat(imageJob.attemptCount).isZero()
        assertThat(imageJob.isTerminal()).isFalse()
    }

    @Test
    fun shouldInitializeAndRefreshLifecycleTimestamps() {
        val imageJob = ImageJob.queued(
            "job-2",
            "idem-2",
            "hash-2",
            "https://example.com/image-2.png",
            Instant.parse("2026-05-05T00:00:00Z")
        )

        imageJob.onCreate()
        val createdAt = imageJob.createdAt
        val updatedAt = imageJob.updatedAt

        Thread.sleep(5L)
        imageJob.onUpdate()

        assertThat(createdAt).isNotNull()
        assertThat(updatedAt).isNotNull()
        assertThat(imageJob.createdAt).isEqualTo(createdAt)
        assertThat(imageJob.updatedAt).isAfterOrEqualTo(updatedAt)
    }

    @Test
    fun shouldPreserveCreatedAtWhenOnCreateRunsAgain() {
        val imageJob = ImageJob.queued(
            "job-3",
            "idem-3",
            "hash-3",
            "https://example.com/image-3.png",
            Instant.parse("2026-05-05T00:00:00Z")
        )

        imageJob.onCreate()
        val createdAt = imageJob.createdAt
        imageJob.onCreate()

        assertThat(imageJob.createdAt).isEqualTo(createdAt)
    }

    @Test
    fun shouldReportTerminalStatusForSucceededAndFailed() {
        val imageJob = ImageJob.queued(
            "job-4",
            "idem-4",
            "hash-4",
            "https://example.com/image-4.png",
            Instant.parse("2026-05-05T00:00:00Z")
        )

        imageJob.status = JobStatus.SUCCEEDED
        assertThat(imageJob.isTerminal()).isTrue()

        imageJob.status = JobStatus.FAILED
        assertThat(imageJob.isTerminal()).isTrue()
    }
}

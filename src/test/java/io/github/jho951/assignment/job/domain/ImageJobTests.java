package io.github.jho951.assignment.job.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ImageJobTests {

    @Test
    void shouldCreateQueuedJobWithExpectedDefaults() {
        Instant nextAttemptAt = Instant.parse("2026-05-05T00:00:00Z");

        ImageJob imageJob = ImageJob.queued(
                "job-1",
                "idem-1",
                "hash-1",
                "https://example.com/image.png",
                nextAttemptAt
        );

        assertThat(imageJob.getJobId()).isEqualTo("job-1");
        assertThat(imageJob.getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(imageJob.getRequestHash()).isEqualTo("hash-1");
        assertThat(imageJob.getImageInputType()).isEqualTo(ImageInputType.URL);
        assertThat(imageJob.getImageUrl()).isEqualTo("https://example.com/image.png");
        assertThat(imageJob.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(imageJob.getNextAttemptAt()).isEqualTo(nextAttemptAt);
        assertThat(imageJob.getAttemptCount()).isZero();
        assertThat(imageJob.isTerminal()).isFalse();
    }

    @Test
    void shouldInitializeAndRefreshLifecycleTimestamps() throws InterruptedException {
        ImageJob imageJob = ImageJob.queued(
                "job-2",
                "idem-2",
                "hash-2",
                "https://example.com/image-2.png",
                Instant.parse("2026-05-05T00:00:00Z")
        );

        imageJob.onCreate();
        Instant createdAt = imageJob.getCreatedAt();
        Instant updatedAt = imageJob.getUpdatedAt();

        Thread.sleep(5L);
        imageJob.onUpdate();

        assertThat(createdAt).isNotNull();
        assertThat(updatedAt).isNotNull();
        assertThat(imageJob.getCreatedAt()).isEqualTo(createdAt);
        assertThat(imageJob.getUpdatedAt()).isAfterOrEqualTo(updatedAt);
    }

    @Test
    void shouldPreserveCreatedAtWhenOnCreateRunsAgain() {
        ImageJob imageJob = ImageJob.queued(
                "job-3",
                "idem-3",
                "hash-3",
                "https://example.com/image-3.png",
                Instant.parse("2026-05-05T00:00:00Z")
        );

        imageJob.onCreate();
        Instant createdAt = imageJob.getCreatedAt();
        imageJob.onCreate();

        assertThat(imageJob.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void shouldReportTerminalStatusForSucceededAndFailed() {
        ImageJob imageJob = ImageJob.queued(
                "job-4",
                "idem-4",
                "hash-4",
                "https://example.com/image-4.png",
                Instant.parse("2026-05-05T00:00:00Z")
        );

        imageJob.setStatus(JobStatus.SUCCEEDED);
        assertThat(imageJob.isTerminal()).isTrue();

        imageJob.setStatus(JobStatus.FAILED);
        assertThat(imageJob.isTerminal()).isTrue();
    }
}

package io.github.jho951.assignment.job.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import io.github.jho951.assignment.config.JobProperties;
import io.github.jho951.assignment.job.domain.ImageJob;
import io.github.jho951.assignment.job.domain.JobFailureCode;
import io.github.jho951.assignment.job.domain.JobStatus;
import io.github.jho951.assignment.job.domain.JobStatusTransitionPolicy;
import io.github.jho951.assignment.job.repository.ImageJobRepository;

@ExtendWith(MockitoExtension.class)
class JobRecoveryServiceTests {

    private static final Instant NOW = Instant.parse("2026-05-05T00:00:00Z");

    @Mock
    private ImageJobRepository imageJobRepository;

    @Mock
    private JobProcessor jobProcessor;

    private JobRecoveryService jobRecoveryService;

    @BeforeEach
    void setUp() {
        jobRecoveryService = new JobRecoveryService(
                imageJobRepository,
                new JobStatusTransitionPolicy(),
                jobProcessor,
                new JobProperties(
                        3,
                        1_000,
                        20,
                        5_000,
                        60_000,
                        false,
                        new JobProperties.Executor(1, 1, 1)
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldRescheduleStaleProcessingJobsWithinAttemptLimit() {
        ImageJob staleJob = processingJob("job-stale", 1);
        when(imageJobRepository.findStaleProcessingJobs(eq(JobStatus.PROCESSING), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(staleJob));
        when(jobProcessor.calculateBackoff(1)).thenReturn(Duration.ofSeconds(2));

        jobRecoveryService.recoverStaleJobs();

        assertThat(staleJob.getStatus()).isEqualTo(JobStatus.RETRY_SCHEDULED);
        assertThat(staleJob.getErrorCode()).isEqualTo(JobFailureCode.WORKER_UNAVAILABLE.name());
        assertThat(staleJob.getErrorMessage()).isEqualTo("Recovered stale processing job");
        assertThat(staleJob.getLeaseUntil()).isNull();
        assertThat(staleJob.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(imageJobRepository).findStaleProcessingJobs(eq(JobStatus.PROCESSING), eq(NOW), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void shouldFailStaleProcessingJobsWhenAttemptLimitIsExceeded() {
        ImageJob staleJob = processingJob("job-exhausted", 3);
        when(imageJobRepository.findStaleProcessingJobs(eq(JobStatus.PROCESSING), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(staleJob));

        jobRecoveryService.recoverStaleJobs();

        assertThat(staleJob.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(staleJob.getErrorCode()).isEqualTo(JobFailureCode.MAX_ATTEMPTS_EXCEEDED.name());
        assertThat(staleJob.getErrorMessage()).isEqualTo("Maximum retry attempts exceeded during stale job recovery");
        assertThat(staleJob.getLeaseUntil()).isNull();
        assertThat(staleJob.getCompletedAt()).isEqualTo(NOW);
        assertThat(staleJob.getExpiresAt()).isEqualTo(NOW.plusSeconds(7 * 24 * 60 * 60L));
    }

    private ImageJob processingJob(String jobId, int attemptCount) {
        ImageJob imageJob = ImageJob.queued(
                jobId,
                "idem-" + jobId,
                "hash-" + jobId,
                "https://example.com/" + jobId + ".png",
                NOW.minusSeconds(10)
        );
        imageJob.setStatus(JobStatus.PROCESSING);
        imageJob.setAttemptCount(attemptCount);
        imageJob.setLeaseUntil(NOW.minusSeconds(1));
        return imageJob;
    }
}

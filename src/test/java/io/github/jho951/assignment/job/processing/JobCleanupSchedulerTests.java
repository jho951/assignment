package io.github.jho951.assignment.job.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
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
import io.github.jho951.assignment.job.domain.JobStatus;
import io.github.jho951.assignment.job.repository.ImageJobRepository;

@ExtendWith(MockitoExtension.class)
class JobCleanupSchedulerTests {

    private static final Instant NOW = Instant.parse("2026-05-05T00:00:00Z");

    @Mock
    private ImageJobRepository imageJobRepository;

    private JobCleanupScheduler jobCleanupScheduler;

    @BeforeEach
    void setUp() {
        jobCleanupScheduler = new JobCleanupScheduler(
                imageJobRepository,
                new JobProperties(
                        3,
                        1_000,
                        15,
                        5_000,
                        60_000,
                        false,
                        new JobProperties.Executor(1, 1, 1)
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldDeleteExpiredTerminalJobsWhenFound() {
        ImageJob expiredSucceeded = ImageJob.queued(
                "job-expired",
                "idem-expired",
                "hash-expired",
                "https://example.com/expired.png",
                NOW.minusSeconds(10)
        );
        expiredSucceeded.setStatus(JobStatus.SUCCEEDED);
        when(imageJobRepository.findExpiredJobs(any(Collection.class), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(expiredSucceeded));

        jobCleanupScheduler.cleanupExpiredJobs();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<JobStatus>> statusesCaptor = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(imageJobRepository).findExpiredJobs(statusesCaptor.capture(), eq(NOW), pageableCaptor.capture());
        assertThat(statusesCaptor.getValue()).containsExactlyInAnyOrder(JobStatus.SUCCEEDED, JobStatus.FAILED);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(15);
        verify(imageJobRepository).deleteAllInBatch(List.of(expiredSucceeded));
    }

    @Test
    void shouldSkipDeletionWhenNoExpiredJobsExist() {
        when(imageJobRepository.findExpiredJobs(any(Collection.class), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of());

        jobCleanupScheduler.cleanupExpiredJobs();

        verify(imageJobRepository, never()).deleteAllInBatch(any(Collection.class));
    }
}

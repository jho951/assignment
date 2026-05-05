package io.github.jho951.assignment.job.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import io.github.jho951.assignment.config.JobProperties;
import io.github.jho951.assignment.job.domain.ImageJob;
import io.github.jho951.assignment.job.domain.JobFailureCode;
import io.github.jho951.assignment.job.domain.JobStatus;
import io.github.jho951.assignment.job.domain.JobStatusTransitionPolicy;
import io.github.jho951.assignment.job.repository.ImageJobRepository;
import io.github.jho951.assignment.job.worker.WorkerClient;
import io.github.jho951.assignment.job.worker.WorkerClientException;
import io.github.jho951.assignment.job.worker.WorkerRemoteStatus;
import io.github.jho951.assignment.job.worker.WorkerStartResult;
import io.github.jho951.assignment.job.worker.WorkerStatusResult;

@ExtendWith(MockitoExtension.class)
class JobProcessorTests {

    private static final Instant NOW = Instant.parse("2026-05-04T06:00:00Z");

    @Mock
    private ImageJobRepository imageJobRepository;

    @Mock
    private WorkerClient workerClient;

    @Mock
    private PlatformTransactionManager platformTransactionManager;

    private JobProcessor jobProcessor;

    @BeforeEach
    void setUp() {
        lenient().when(platformTransactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        jobProcessor = new JobProcessor(
                imageJobRepository,
                new JobStatusTransitionPolicy(),
                workerClient,
                new JobProperties(
                        3,
                        50,
                        20,
                        5_000,
                        60_000,
                        false,
                        new JobProperties.Executor(1, 1, 1)
                ),
                Clock.fixed(NOW, ZoneOffset.UTC),
                platformTransactionManager
        );
    }

    @Test
    void shouldFindDueJobIdsUsingConfiguredStatusesAndBatchSize() {
        ImageJob queuedJob = queuedJob("job-1", NOW.minusSeconds(1));
        ImageJob retryJob = queuedJob("job-2", NOW.minusSeconds(2));
        retryJob.setStatus(JobStatus.RETRY_SCHEDULED);

        when(imageJobRepository.findDueJobs(anyCollection(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(queuedJob, retryJob));

        List<String> dueJobIds = jobProcessor.findDueJobIds();

        assertThat(dueJobIds).containsExactly("job-1", "job-2");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<JobStatus>> statusesCaptor = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(imageJobRepository).findDueJobs(statusesCaptor.capture(), nowCaptor.capture(), pageableCaptor.capture());
        assertThat(statusesCaptor.getValue()).containsExactlyInAnyOrder(
                JobStatus.QUEUED,
                JobStatus.RETRY_SCHEDULED,
                JobStatus.PROCESSING
        );
        assertThat(nowCaptor.getValue()).isEqualTo(NOW);
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void shouldClaimQueuedJobForProcessing() {
        ImageJob imageJob = queuedJob("job-claim", NOW.minusSeconds(1));
        when(imageJobRepository.findByJobId("job-claim")).thenReturn(Optional.of(imageJob));

        boolean claimed = jobProcessor.claimJobForProcessing("job-claim");

        assertThat(claimed).isTrue();
        assertThat(imageJob.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(imageJob.getAttemptCount()).isEqualTo(1);
        assertThat(imageJob.getLeaseUntil()).isEqualTo(NOW.plusMillis(5_000));
        assertThat(imageJob.getNextAttemptAt()).isEqualTo(NOW.plusMillis(5_000));
        verify(imageJobRepository).save(imageJob);
    }

    @Test
    void shouldClaimRetryScheduledJobForProcessing() {
        ImageJob imageJob = queuedJob("job-retry", NOW.minusSeconds(1));
        imageJob.setStatus(JobStatus.RETRY_SCHEDULED);
        imageJob.setAttemptCount(1);
        when(imageJobRepository.findByJobId("job-retry")).thenReturn(Optional.of(imageJob));

        boolean claimed = jobProcessor.claimJobForProcessing("job-retry");

        assertThat(claimed).isTrue();
        assertThat(imageJob.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(imageJob.getAttemptCount()).isEqualTo(2);
        assertThat(imageJob.getNextAttemptAt()).isEqualTo(NOW.plusMillis(5_000));
        verify(imageJobRepository).save(imageJob);
    }

    @Test
    void shouldClaimProcessingJobForSingleStepContinuationWhenLeaseIsReleased() {
        ImageJob imageJob = processingJob("job-processing-continue", 1);
        imageJob.setLeaseUntil(null);
        imageJob.setExternalJobId("worker-continue");
        when(imageJobRepository.findByJobId("job-processing-continue")).thenReturn(Optional.of(imageJob));

        boolean claimed = jobProcessor.claimJobForProcessing("job-processing-continue");

        assertThat(claimed).isTrue();
        assertThat(imageJob.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(imageJob.getAttemptCount()).isEqualTo(2);
        assertThat(imageJob.getLeaseUntil()).isEqualTo(NOW.plusMillis(5_000));
        assertThat(imageJob.getNextAttemptAt()).isEqualTo(NOW.plusMillis(5_000));
    }

    @Test
    void shouldNotClaimProcessingJobWhenLeaseIsStillHeld() {
        ImageJob imageJob = processingJob("job-processing-leased", 1);
        imageJob.setExternalJobId("worker-leased");
        when(imageJobRepository.findByJobId("job-processing-leased")).thenReturn(Optional.of(imageJob));

        boolean claimed = jobProcessor.claimJobForProcessing("job-processing-leased");

        assertThat(claimed).isFalse();
        verify(imageJobRepository, never()).save(any(ImageJob.class));
    }

    @Test
    void shouldNotClaimJobWhenNextAttemptIsInFuture() {
        ImageJob imageJob = queuedJob("job-future", NOW.plusSeconds(30));
        when(imageJobRepository.findByJobId("job-future")).thenReturn(Optional.of(imageJob));

        boolean claimed = jobProcessor.claimJobForProcessing("job-future");

        assertThat(claimed).isFalse();
        assertThat(imageJob.getStatus()).isEqualTo(JobStatus.QUEUED);
        verify(imageJobRepository, never()).save(any(ImageJob.class));
    }

    @Test
    void shouldIgnoreClaimedJobWhenItIsMissingOrNotProcessing() {
        when(imageJobRepository.findByJobId("missing-job")).thenReturn(Optional.empty());

        jobProcessor.processClaimedJob("missing-job");

        verifyNoInteractions(workerClient);
    }

    @Test
    void shouldMarkJobSucceededWhenWorkerCompletesImmediately() {
        ImageJob imageJob = processingJob("job-success", 1);
        when(imageJobRepository.findByJobId("job-success")).thenReturn(Optional.of(imageJob));
        when(workerClient.startProcess(imageJob.getImageUrl()))
                .thenReturn(new WorkerStartResult("worker-1", WorkerRemoteStatus.COMPLETED));
        when(workerClient.getProcessStatus("worker-1"))
                .thenReturn(new WorkerStatusResult("worker-1", WorkerRemoteStatus.COMPLETED, "https://cdn.example/result.png"));

        jobProcessor.processClaimedJob("job-success");

        assertThat(imageJob.getExternalJobId()).isEqualTo("worker-1");
        assertThat(imageJob.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(imageJob.getResult()).isEqualTo("https://cdn.example/result.png");
        assertThat(imageJob.getErrorCode()).isNull();
        assertThat(imageJob.getErrorMessage()).isNull();
        assertThat(imageJob.getLeaseUntil()).isNull();
        assertThat(imageJob.getCompletedAt()).isEqualTo(NOW);
        assertThat(imageJob.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        verify(imageJobRepository, atLeast(2)).save(imageJob);
    }

    @Test
    void shouldMarkJobFailedWhenWorkerReportsFailed() {
        ImageJob imageJob = processingJob("job-failed", 1);
        when(imageJobRepository.findByJobId("job-failed")).thenReturn(Optional.of(imageJob));
        when(workerClient.startProcess(imageJob.getImageUrl()))
                .thenReturn(new WorkerStartResult("worker-2", WorkerRemoteStatus.COMPLETED));
        when(workerClient.getProcessStatus("worker-2"))
                .thenReturn(new WorkerStatusResult("worker-2", WorkerRemoteStatus.FAILED, null));

        jobProcessor.processClaimedJob("job-failed");

        assertThat(imageJob.getExternalJobId()).isEqualTo("worker-2");
        assertThat(imageJob.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(imageJob.getErrorCode()).isEqualTo(JobFailureCode.INTERNAL_ERROR.name());
        assertThat(imageJob.getErrorMessage()).isEqualTo("Mock Worker reported FAILED");
        assertThat(imageJob.getLeaseUntil()).isNull();
        assertThat(imageJob.getCompletedAt()).isEqualTo(NOW);
        assertThat(imageJob.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void shouldKeepProcessingStatusAndRescheduleWhenWorkerStillProcessingAfterStart() {
        ImageJob imageJob = processingJob("job-still-processing-start", 1);
        when(imageJobRepository.findByJobId("job-still-processing-start")).thenReturn(Optional.of(imageJob));
        when(workerClient.startProcess(imageJob.getImageUrl()))
                .thenReturn(new WorkerStartResult("worker-processing", WorkerRemoteStatus.PROCESSING));

        jobProcessor.processClaimedJob("job-still-processing-start");

        assertThat(imageJob.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(imageJob.getExternalJobId()).isEqualTo("worker-processing");
        assertThat(imageJob.getLeaseUntil()).isNull();
        assertThat(imageJob.getNextAttemptAt()).isEqualTo(NOW.plusMillis(50));
        assertThat(imageJob.getCompletedAt()).isNull();
        verify(workerClient, never()).getProcessStatus(any());
    }

    @Test
    void shouldKeepProcessingStatusAndRescheduleWhenExistingWorkerJobIsStillProcessing() {
        ImageJob imageJob = processingJob("job-still-processing-poll", 2);
        imageJob.setExternalJobId("worker-existing");
        when(imageJobRepository.findByJobId("job-still-processing-poll")).thenReturn(Optional.of(imageJob));
        when(workerClient.getProcessStatus("worker-existing"))
                .thenReturn(new WorkerStatusResult("worker-existing", WorkerRemoteStatus.PROCESSING, null));

        jobProcessor.processClaimedJob("job-still-processing-poll");

        assertThat(imageJob.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(imageJob.getLeaseUntil()).isNull();
        assertThat(imageJob.getNextAttemptAt()).isEqualTo(NOW.plusMillis(50));
        assertThat(imageJob.getCompletedAt()).isNull();
    }

    @Test
    void shouldScheduleRetryForRetryableWorkerFailure() {
        ImageJob imageJob = processingJob("job-retryable", 1);
        when(imageJobRepository.findByJobId("job-retryable")).thenReturn(Optional.of(imageJob));
        when(workerClient.startProcess(imageJob.getImageUrl()))
                .thenThrow(new WorkerClientException(JobFailureCode.WORKER_TIMEOUT, true, "Worker timed out"));

        jobProcessor.processClaimedJob("job-retryable");

        assertThat(imageJob.getStatus()).isEqualTo(JobStatus.RETRY_SCHEDULED);
        assertThat(imageJob.getErrorCode()).isEqualTo(JobFailureCode.WORKER_TIMEOUT.name());
        assertThat(imageJob.getErrorMessage()).isEqualTo("Worker timed out");
        assertThat(imageJob.getLeaseUntil()).isNull();
        assertThat(imageJob.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void shouldFailWhenRetryableWorkerFailureExceedsMaxAttempts() {
        ImageJob imageJob = processingJob("job-max-attempts", 3);
        when(imageJobRepository.findByJobId("job-max-attempts")).thenReturn(Optional.of(imageJob));
        when(workerClient.startProcess(imageJob.getImageUrl()))
                .thenThrow(new WorkerClientException(JobFailureCode.WORKER_TIMEOUT, true, "Worker timed out"));

        jobProcessor.processClaimedJob("job-max-attempts");

        assertThat(imageJob.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(imageJob.getErrorCode()).isEqualTo(JobFailureCode.MAX_ATTEMPTS_EXCEEDED.name());
        assertThat(imageJob.getErrorMessage()).isEqualTo("Maximum retry attempts exceeded");
        assertThat(imageJob.getCompletedAt()).isEqualTo(NOW);
        assertThat(imageJob.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void shouldFailWhenWorkerFailureIsNotRetryable() {
        ImageJob imageJob = processingJob("job-bad-request", 1);
        when(imageJobRepository.findByJobId("job-bad-request")).thenReturn(Optional.of(imageJob));
        when(workerClient.startProcess(imageJob.getImageUrl()))
                .thenThrow(new WorkerClientException(JobFailureCode.WORKER_BAD_REQUEST, false, "Bad request"));

        jobProcessor.processClaimedJob("job-bad-request");

        assertThat(imageJob.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(imageJob.getErrorCode()).isEqualTo(JobFailureCode.WORKER_BAD_REQUEST.name());
        assertThat(imageJob.getErrorMessage()).isEqualTo("Bad request");
    }

    @Test
    void shouldFailClaimedJobWhenPollingThreadIsInterrupted() {
        ImageJob imageJob = processingJob("job-interrupted", 1);
        imageJob.setExternalJobId("worker-3");
        when(imageJobRepository.findByJobId("job-interrupted")).thenReturn(Optional.of(imageJob));

        try {
            Thread.currentThread().interrupt();

            jobProcessor.processClaimedJob("job-interrupted");

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(imageJob.getStatus()).isEqualTo(JobStatus.RETRY_SCHEDULED);
            assertThat(imageJob.getErrorCode()).isEqualTo(JobFailureCode.INTERNAL_ERROR.name());
            assertThat(imageJob.getErrorMessage()).contains("interrupted");
            assertThat(imageJob.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
            assertThat(imageJob.getLeaseUntil()).isNull();
            verifyNoInteractions(workerClient);
        }
        finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldCalculateBackoffByAttemptCount() {
        assertThat(jobProcessor.calculateBackoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(jobProcessor.calculateBackoff(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(jobProcessor.calculateBackoff(3)).isEqualTo(Duration.ofSeconds(30));
        assertThat(jobProcessor.calculateBackoff(99)).isEqualTo(Duration.ofSeconds(30));
    }

    private ImageJob queuedJob(String jobId, Instant nextAttemptAt) {
        return ImageJob.queued(
                jobId,
                "idem-" + jobId,
                "hash-" + jobId,
                "https://example.com/" + jobId + ".png",
                nextAttemptAt
        );
    }

    private ImageJob processingJob(String jobId, int attemptCount) {
        ImageJob imageJob = queuedJob(jobId, NOW.minusSeconds(1));
        imageJob.setStatus(JobStatus.PROCESSING);
        imageJob.setAttemptCount(attemptCount);
        imageJob.setLeaseUntil(NOW.plusSeconds(30));
        return imageJob;
    }
}

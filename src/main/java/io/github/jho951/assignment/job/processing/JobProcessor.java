package io.github.jho951.assignment.job.processing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

@Component
public class JobProcessor {

    private static final Duration RESULT_RETENTION = Duration.ofDays(7);

    private final ImageJobRepository imageJobRepository;
    private final JobStatusTransitionPolicy transitionPolicy;
    private final WorkerClient workerClient;
    private final JobProperties jobProperties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public JobProcessor(
            ImageJobRepository imageJobRepository,
            JobStatusTransitionPolicy transitionPolicy,
            WorkerClient workerClient,
            JobProperties jobProperties,
            Clock clock,
            PlatformTransactionManager platformTransactionManager
    ) {
        this.imageJobRepository = imageJobRepository;
        this.transitionPolicy = transitionPolicy;
        this.workerClient = workerClient;
        this.jobProperties = jobProperties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(platformTransactionManager);
    }

    public List<String> findDueJobIds() {
        return imageJobRepository.findDueJobs(
                        List.of(JobStatus.QUEUED, JobStatus.RETRY_SCHEDULED, JobStatus.PROCESSING),
                        Instant.now(clock),
                        PageRequest.of(0, jobProperties.batchSize())
                ).stream()
                .map(ImageJob::getJobId)
                .toList();
    }

    public boolean claimJobForProcessing(String jobId) {
        Instant now = Instant.now(clock);
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            ImageJob imageJob = imageJobRepository.findByJobId(jobId).orElse(null);
            if (imageJob == null) {
                return false;
            }
            if (imageJob.getNextAttemptAt() != null && imageJob.getNextAttemptAt().isAfter(now)) {
                return false;
            }

            if (imageJob.getStatus() == JobStatus.QUEUED || imageJob.getStatus() == JobStatus.RETRY_SCHEDULED) {
                transitionPolicy.assertTransition(imageJob.getStatus(), JobStatus.PROCESSING);
                imageJob.setStatus(JobStatus.PROCESSING);
            }
            else if (imageJob.getStatus() == JobStatus.PROCESSING) {
                if (imageJob.getLeaseUntil() != null || imageJob.getExternalJobId() == null) {
                    return false;
                }
            }
            else {
                return false;
            }

            imageJob.setAttemptCount(imageJob.getAttemptCount() + 1);
            imageJob.setLeaseUntil(now.plusMillis(jobProperties.leaseTimeoutMs()));
            imageJob.setNextAttemptAt(now.plusMillis(jobProperties.leaseTimeoutMs()));
            imageJob.setErrorCode(null);
            imageJob.setErrorMessage(null);
            imageJobRepository.save(imageJob);
            return true;
        }));
    }

    public void processClaimedJob(String jobId) {
        try {
            ImageJob imageJob = loadJob(jobId);
            if (imageJob == null || imageJob.getStatus() != JobStatus.PROCESSING) {
                return;
            }

            if (Thread.currentThread().isInterrupted()) {
                handleInterruptedExecution(jobId);
                return;
            }

            if (imageJob.getExternalJobId() == null) {
                WorkerStartResult startResult = workerClient.startProcess(imageJob.getImageUrl());
                if (startResult.status() == WorkerRemoteStatus.COMPLETED || startResult.status() == WorkerRemoteStatus.FAILED) {
                    recordWorkerJobId(jobId, startResult.workerJobId());
                    completeFromWorkerStatus(workerClient.getProcessStatus(startResult.workerJobId()), jobId);
                    return;
                }

                rescheduleProcessingPoll(jobId, startResult.workerJobId());
                return;
            }

            WorkerStatusResult statusResult = workerClient.getProcessStatus(imageJob.getExternalJobId());
            if (statusResult.status() == WorkerRemoteStatus.PROCESSING) {
                rescheduleProcessingPoll(jobId, imageJob.getExternalJobId());
                return;
            }

            completeFromWorkerStatus(statusResult, jobId);
        }
        catch (WorkerClientException exception) {
            handleWorkerFailure(jobId, exception);
        }
        catch (Exception exception) {
            handleUnexpectedFailure(jobId, exception);
        }
    }

    private void completeFromWorkerStatus(WorkerStatusResult statusResult, String jobId) {
        if (statusResult.status() == WorkerRemoteStatus.COMPLETED) {
            markSucceeded(jobId, statusResult.result());
            return;
        }

        markFailed(jobId, JobFailureCode.INTERNAL_ERROR, "Mock Worker reported FAILED");
    }

    private void recordWorkerJobId(String jobId, String workerJobId) {
        transactionTemplate.executeWithoutResult(status -> {
            ImageJob imageJob = requireJob(jobId);
            imageJob.setExternalJobId(workerJobId);
            imageJobRepository.save(imageJob);
        });
    }

    private void rescheduleProcessingPoll(String jobId, String workerJobId) {
        transactionTemplate.executeWithoutResult(status -> {
            ImageJob imageJob = requireJob(jobId);
            if (imageJob.getStatus() == JobStatus.PROCESSING) {
                imageJob.setExternalJobId(workerJobId);
                imageJob.setLeaseUntil(null);
                imageJob.setNextAttemptAt(Instant.now(clock).plusMillis(Math.max(jobProperties.pollIntervalMs(), 1L)));
                imageJobRepository.save(imageJob);
            }
        });
    }

    private void handleWorkerFailure(String jobId, WorkerClientException exception) {
        transactionTemplate.executeWithoutResult(status -> {
            ImageJob imageJob = requireJob(jobId);
            if (imageJob.getStatus() != JobStatus.PROCESSING) {
                return;
            }

            if (exception.isRetryable() && imageJob.getAttemptCount() < jobProperties.maxAttempts()) {
                transitionPolicy.assertTransition(JobStatus.PROCESSING, JobStatus.RETRY_SCHEDULED);
                imageJob.setStatus(JobStatus.RETRY_SCHEDULED);
                imageJob.setErrorCode(exception.getFailureCode().name());
                imageJob.setErrorMessage(exception.getMessage());
                imageJob.setLeaseUntil(null);
                imageJob.setNextAttemptAt(Instant.now(clock).plus(calculateBackoff(imageJob.getAttemptCount())));
                imageJobRepository.save(imageJob);
                return;
            }

            if (exception.isRetryable()) {
                markFailed(imageJob, JobFailureCode.MAX_ATTEMPTS_EXCEEDED, "Maximum retry attempts exceeded");
                return;
            }

            markFailed(imageJob, exception.getFailureCode(), exception.getMessage());
        });
    }

    private void handleUnexpectedFailure(String jobId, Exception exception) {
        transactionTemplate.executeWithoutResult(status -> {
            ImageJob imageJob = requireJob(jobId);
            if (imageJob.getStatus() == JobStatus.PROCESSING) {
                markFailed(imageJob, JobFailureCode.INTERNAL_ERROR, exception.getMessage() == null
                        ? "Unexpected internal error"
                        : exception.getMessage());
            }
        });
    }

    private void handleInterruptedExecution(String jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            ImageJob imageJob = requireJob(jobId);
            if (imageJob.getStatus() != JobStatus.PROCESSING) {
                return;
            }

            if (imageJob.getAttemptCount() < jobProperties.maxAttempts()) {
                transitionPolicy.assertTransition(JobStatus.PROCESSING, JobStatus.RETRY_SCHEDULED);
                imageJob.setStatus(JobStatus.RETRY_SCHEDULED);
                imageJob.setErrorCode(JobFailureCode.INTERNAL_ERROR.name());
                imageJob.setErrorMessage("Job polling thread was interrupted before completion");
                imageJob.setLeaseUntil(null);
                imageJob.setNextAttemptAt(Instant.now(clock).plus(calculateBackoff(imageJob.getAttemptCount())));
                imageJobRepository.save(imageJob);
                return;
            }

            markFailed(imageJob, JobFailureCode.MAX_ATTEMPTS_EXCEEDED, "Maximum retry attempts exceeded");
        });
    }

    private void markSucceeded(String jobId, String result) {
        transactionTemplate.executeWithoutResult(status -> {
            ImageJob imageJob = requireJob(jobId);
            markSucceeded(imageJob, result);
        });
    }

    void markSucceeded(ImageJob imageJob, String result) {
        transitionPolicy.assertTransition(imageJob.getStatus(), JobStatus.SUCCEEDED);
        Instant now = Instant.now(clock);
        imageJob.setStatus(JobStatus.SUCCEEDED);
        imageJob.setResult(result);
        imageJob.setErrorCode(null);
        imageJob.setErrorMessage(null);
        imageJob.setLeaseUntil(null);
        imageJob.setCompletedAt(now);
        imageJob.setExpiresAt(now.plus(RESULT_RETENTION));
        imageJobRepository.save(imageJob);
    }

    private void markFailed(String jobId, JobFailureCode code, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            ImageJob imageJob = requireJob(jobId);
            markFailed(imageJob, code, message);
        });
    }

    void markFailed(ImageJob imageJob, JobFailureCode code, String message) {
        transitionPolicy.assertTransition(imageJob.getStatus(), JobStatus.FAILED);
        Instant now = Instant.now(clock);
        imageJob.setStatus(JobStatus.FAILED);
        imageJob.setErrorCode(code.name());
        imageJob.setErrorMessage(message);
        imageJob.setLeaseUntil(null);
        imageJob.setCompletedAt(now);
        imageJob.setExpiresAt(now.plus(RESULT_RETENTION));
        imageJobRepository.save(imageJob);
    }

    Duration calculateBackoff(int attemptCount) {
        return switch (attemptCount) {
            case 1 -> Duration.ofSeconds(2);
            case 2 -> Duration.ofSeconds(10);
            default -> Duration.ofSeconds(30);
        };
    }

    private ImageJob loadJob(String jobId) {
        return imageJobRepository.findByJobId(jobId).orElse(null);
    }

    private ImageJob requireJob(String jobId) {
        return imageJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new IllegalStateException("Image job not found: " + jobId));
    }
}

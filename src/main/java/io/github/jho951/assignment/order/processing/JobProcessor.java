package io.github.jho951.assignment.order.processing;

import io.github.jho951.assignment.brokerage.BrokerageClient;
import io.github.jho951.assignment.brokerage.BrokerageClientException;
import io.github.jho951.assignment.brokerage.BrokerageRemoteStatus;
import io.github.jho951.assignment.brokerage.BrokerageStatusResult;
import io.github.jho951.assignment.config.JobProperties;
import io.github.jho951.assignment.order.domain.JobFailureCode;
import io.github.jho951.assignment.order.domain.JobStatus;
import io.github.jho951.assignment.order.domain.JobStatusTransitionPolicy;
import io.github.jho951.assignment.order.domain.StockOrderJob;
import io.github.jho951.assignment.order.repository.StockOrderJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class JobProcessor {

    private static final Duration RESULT_RETENTION = Duration.ofDays(7);

    private final StockOrderJobRepository stockOrderJobRepository;
    private final JobStatusTransitionPolicy transitionPolicy;
    private final BrokerageClient brokerageClient;
    private final JobProperties jobProperties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public JobProcessor(
        StockOrderJobRepository stockOrderJobRepository,
        JobStatusTransitionPolicy transitionPolicy,
        BrokerageClient brokerageClient,
        JobProperties jobProperties,
        Clock clock,
        PlatformTransactionManager platformTransactionManager
    ) {
        this.stockOrderJobRepository = stockOrderJobRepository;
        this.transitionPolicy = transitionPolicy;
        this.brokerageClient = brokerageClient;
        this.jobProperties = jobProperties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(platformTransactionManager);
    }

    public List<String> findDueJobIds() {
        return stockOrderJobRepository.findDueJobs(
                List.of(JobStatus.QUEUED, JobStatus.RETRY_SCHEDULED, JobStatus.PROCESSING),
                Instant.now(clock),
                PageRequest.of(0, jobProperties.batchSize())
            )
            .stream()
            .map(StockOrderJob::getJobId)
            .toList();
    }

    public boolean claimJobForProcessing(String jobId) {
        Instant now = Instant.now(clock);
        Boolean claimed = transactionTemplate.execute(status -> {
            StockOrderJob job = stockOrderJobRepository.findByJobId(jobId).orElse(null);
            if (job == null) {
                return false;
            }
            if (job.getNextAttemptAt() != null && job.getNextAttemptAt().isAfter(now)) {
                return false;
            }

            if (job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.RETRY_SCHEDULED) {
                transitionPolicy.assertTransition(job.getStatus(), JobStatus.PROCESSING);
                job.setStatus(JobStatus.PROCESSING);
            } else if (job.getStatus() == JobStatus.PROCESSING) {
                if (job.getLeaseUntil() != null || job.getExternalOrderId() == null) {
                    return false;
                }
            } else {
                return false;
            }

            job.setAttemptCount(job.getAttemptCount() + 1);
            job.setLeaseUntil(now.plusMillis(jobProperties.leaseTimeoutMs()));
            job.setNextAttemptAt(now.plusMillis(jobProperties.leaseTimeoutMs()));
            job.setErrorCode(null);
            job.setErrorMessage(null);
            stockOrderJobRepository.save(job);
            return true;
        });
        return Boolean.TRUE.equals(claimed);
    }

    public void processClaimedJob(String jobId) {
        try {
            StockOrderJob job = loadJob(jobId);
            if (job == null || job.getStatus() != JobStatus.PROCESSING) {
                return;
            }

            if (Thread.currentThread().isInterrupted()) {
                handleInterruptedExecution(jobId);
                return;
            }

            if (job.getExternalOrderId() == null) {
                var startResult = brokerageClient.submitOrder(job.toBrokerageOrderRequest());
                if (startResult.status().isTerminal()) {
                    recordBrokerageState(jobId, startResult.brokerageOrderId(), startResult.status(), startResult.filledQuantity(),
                        startResult.remainingQuantity(), startResult.averageExecutedPrice());
                    completeFromBrokerageStatus(startResult.toStatusResult(), jobId);
                    return;
                }

                rescheduleProcessingPoll(
                    jobId,
                    startResult.brokerageOrderId(),
                    startResult.status(),
                    startResult.filledQuantity(),
                    startResult.remainingQuantity(),
                    startResult.averageExecutedPrice()
                );
                return;
            }

            String externalOrderId = job.getExternalOrderId();
            BrokerageStatusResult statusResult = brokerageClient.getOrderStatus(externalOrderId);
            if (!statusResult.status().isTerminal()) {
                rescheduleProcessingPoll(
                    jobId,
                    externalOrderId,
                    statusResult.status(),
                    statusResult.filledQuantity(),
                    statusResult.remainingQuantity(),
                    statusResult.averageExecutedPrice()
                );
                return;
            }

            completeFromBrokerageStatus(statusResult, jobId);
        } catch (BrokerageClientException exception) {
            handleBrokerageFailure(jobId, exception);
        } catch (Exception exception) {
            handleUnexpectedFailure(jobId, exception);
        }
    }

    public Duration calculateBackoff(int attemptCount) {
        return switch (attemptCount) {
            case 1 -> Duration.ofSeconds(2);
            case 2 -> Duration.ofSeconds(10);
            default -> Duration.ofSeconds(30);
        };
    }

    public void markSucceeded(StockOrderJob job, BrokerageStatusResult result) {
        transitionPolicy.assertTransition(job.getStatus(), JobStatus.SUCCEEDED);
        Instant now = Instant.now(clock);
        job.setStatus(JobStatus.SUCCEEDED);
        job.setExecutionStatus(result.status());
        job.setFilledQuantity(result.filledQuantity());
        job.setRemainingQuantity(result.remainingQuantity());
        job.setAverageExecutedPrice(result.averageExecutedPrice());
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setLeaseUntil(null);
        job.setCompletedAt(now);
        job.setExpiresAt(now.plus(RESULT_RETENTION));
        stockOrderJobRepository.save(job);
    }

    public void markFailed(
        StockOrderJob job,
        JobFailureCode code,
        String message,
        BrokerageRemoteStatus executionStatus,
        Integer filledQuantity,
        Integer remainingQuantity
    ) {
        transitionPolicy.assertTransition(job.getStatus(), JobStatus.FAILED);
        Instant now = Instant.now(clock);
        job.setStatus(JobStatus.FAILED);
        job.setExecutionStatus(executionStatus);
        if (filledQuantity != null) {
            job.setFilledQuantity(filledQuantity);
        }
        if (remainingQuantity != null) {
            job.setRemainingQuantity(remainingQuantity);
        }
        job.setErrorCode(code.name());
        job.setErrorMessage(message);
        job.setLeaseUntil(null);
        job.setCompletedAt(now);
        job.setExpiresAt(now.plus(RESULT_RETENTION));
        stockOrderJobRepository.save(job);
    }

    private void completeFromBrokerageStatus(BrokerageStatusResult statusResult, String jobId) {
        switch (statusResult.status()) {
            case FILLED -> markSucceeded(jobId, statusResult);
            case REJECTED -> markFailed(
                jobId,
                JobFailureCode.BROKERAGE_ORDER_REJECTED,
                statusResult.message() == null ? "Brokerage reported REJECTED" : statusResult.message(),
                statusResult.status(),
                statusResult.filledQuantity(),
                statusResult.remainingQuantity()
            );
            case CANCELLED -> markFailed(
                jobId,
                JobFailureCode.BROKERAGE_ORDER_CANCELLED,
                statusResult.message() == null ? "Brokerage reported CANCELLED" : statusResult.message(),
                statusResult.status(),
                statusResult.filledQuantity(),
                statusResult.remainingQuantity()
            );
            default -> throw new IllegalStateException("Non-terminal brokerage status reached completion handler: " + statusResult.status());
        }
    }

    private void recordBrokerageState(
        String jobId,
        String brokerageOrderId,
        BrokerageRemoteStatus executionStatus,
        int filledQuantity,
        int remainingQuantity,
        java.math.BigDecimal averageExecutedPrice
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            StockOrderJob job = requireJob(jobId);
            job.setExternalOrderId(brokerageOrderId);
            job.setExecutionStatus(executionStatus);
            job.setFilledQuantity(filledQuantity);
            job.setRemainingQuantity(remainingQuantity);
            job.setAverageExecutedPrice(averageExecutedPrice);
            stockOrderJobRepository.save(job);
        });
    }

    private void rescheduleProcessingPoll(
        String jobId,
        String brokerageOrderId,
        BrokerageRemoteStatus executionStatus,
        int filledQuantity,
        int remainingQuantity,
        java.math.BigDecimal averageExecutedPrice
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            StockOrderJob job = requireJob(jobId);
            if (job.getStatus() == JobStatus.PROCESSING) {
                job.setExternalOrderId(brokerageOrderId);
                job.setExecutionStatus(executionStatus);
                job.setFilledQuantity(filledQuantity);
                job.setRemainingQuantity(remainingQuantity);
                job.setAverageExecutedPrice(averageExecutedPrice);
                job.setLeaseUntil(null);
                job.setNextAttemptAt(Instant.now(clock).plusMillis(Math.max(jobProperties.pollIntervalMs(), 1L)));
                stockOrderJobRepository.save(job);
            }
        });
    }

    private void handleBrokerageFailure(String jobId, BrokerageClientException exception) {
        transactionTemplate.executeWithoutResult(status -> {
            StockOrderJob job = requireJob(jobId);
            if (job.getStatus() != JobStatus.PROCESSING) {
                return;
            }

            if (exception.isRetryable() && job.getAttemptCount() < jobProperties.maxAttempts()) {
                transitionPolicy.assertTransition(JobStatus.PROCESSING, JobStatus.RETRY_SCHEDULED);
                job.setStatus(JobStatus.RETRY_SCHEDULED);
                job.setErrorCode(exception.getFailureCode().name());
                job.setErrorMessage(exception.getMessage());
                job.setLeaseUntil(null);
                job.setNextAttemptAt(Instant.now(clock).plus(calculateBackoff(job.getAttemptCount())));
                stockOrderJobRepository.save(job);
                return;
            }

            if (exception.isRetryable()) {
                markFailed(job, JobFailureCode.MAX_ATTEMPTS_EXCEEDED, "Maximum retry attempts exceeded", job.getExecutionStatus(), null, null);
                return;
            }

            markFailed(job, exception.getFailureCode(), exception.getMessage(), job.getExecutionStatus(), null, null);
        });
    }

    private void handleUnexpectedFailure(String jobId, Exception exception) {
        transactionTemplate.executeWithoutResult(status -> {
            StockOrderJob job = requireJob(jobId);
            if (job.getStatus() == JobStatus.PROCESSING) {
                markFailed(
                    job,
                    JobFailureCode.INTERNAL_ERROR,
                    exception.getMessage() == null ? "Unexpected internal error" : exception.getMessage(),
                    job.getExecutionStatus(),
                    null,
                    null
                );
            }
        });
    }

    private void handleInterruptedExecution(String jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            StockOrderJob job = requireJob(jobId);
            if (job.getStatus() != JobStatus.PROCESSING) {
                return;
            }

            if (job.getAttemptCount() < jobProperties.maxAttempts()) {
                transitionPolicy.assertTransition(JobStatus.PROCESSING, JobStatus.RETRY_SCHEDULED);
                job.setStatus(JobStatus.RETRY_SCHEDULED);
                job.setErrorCode(JobFailureCode.INTERNAL_ERROR.name());
                job.setErrorMessage("Order processing thread was interrupted before completion");
                job.setLeaseUntil(null);
                job.setNextAttemptAt(Instant.now(clock).plus(calculateBackoff(job.getAttemptCount())));
                stockOrderJobRepository.save(job);
                return;
            }

            markFailed(job, JobFailureCode.MAX_ATTEMPTS_EXCEEDED, "Maximum retry attempts exceeded", job.getExecutionStatus(), null, null);
        });
    }

    private void markSucceeded(String jobId, BrokerageStatusResult result) {
        transactionTemplate.executeWithoutResult(status -> markSucceeded(requireJob(jobId), result));
    }

    private void markFailed(
        String jobId,
        JobFailureCode code,
        String message,
        BrokerageRemoteStatus executionStatus,
        Integer filledQuantity,
        Integer remainingQuantity
    ) {
        transactionTemplate.executeWithoutResult(status ->
            markFailed(requireJob(jobId), code, message, executionStatus, filledQuantity, remainingQuantity)
        );
    }

    private StockOrderJob loadJob(String jobId) {
        return stockOrderJobRepository.findByJobId(jobId).orElse(null);
    }

    private StockOrderJob requireJob(String jobId) {
        return stockOrderJobRepository.findByJobId(jobId)
            .orElseThrow(() -> new IllegalStateException("Stock order job not found: " + jobId));
    }
}

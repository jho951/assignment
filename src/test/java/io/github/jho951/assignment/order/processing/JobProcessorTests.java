package io.github.jho951.assignment.order.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.jho951.assignment.brokerage.BrokerageClient;
import io.github.jho951.assignment.brokerage.BrokerageClientException;
import io.github.jho951.assignment.brokerage.BrokerageRemoteStatus;
import io.github.jho951.assignment.brokerage.BrokerageStartResult;
import io.github.jho951.assignment.brokerage.BrokerageStatusResult;
import io.github.jho951.assignment.config.JobProperties;
import io.github.jho951.assignment.order.domain.BrokerageOrderSide;
import io.github.jho951.assignment.order.domain.BrokerageOrderType;
import io.github.jho951.assignment.order.domain.JobFailureCode;
import io.github.jho951.assignment.order.domain.JobStatus;
import io.github.jho951.assignment.order.domain.JobStatusTransitionPolicy;
import io.github.jho951.assignment.order.domain.StockOrderJob;
import io.github.jho951.assignment.order.repository.StockOrderJobRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class JobProcessorTests {

    private static final Instant NOW = Instant.parse("2026-05-07T06:00:00Z");

    @Mock
    private StockOrderJobRepository repository;

    @Mock
    private BrokerageClient brokerageClient;

    private JobProcessor jobProcessor;

    @BeforeEach
    void setUp() {
        jobProcessor = new JobProcessor(
            repository,
            new JobStatusTransitionPolicy(),
            brokerageClient,
            new JobProperties(3, 50, 20, 5_000, 60_000, false, new JobProperties.ExecutorProperties(1, 1, 1)),
            Clock.fixed(NOW, ZoneOffset.UTC),
            testTransactionManager()
        );
    }

    @Test
    void shouldClaimQueuedJobForProcessing() {
        StockOrderJob job = queuedJob("order_1", NOW.minusSeconds(1));
        when(repository.findByJobId("order_1")).thenReturn(Optional.of(job));

        boolean claimed = jobProcessor.claimJobForProcessing("order_1");

        assertThat(claimed).isTrue();
        assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getLeaseUntil()).isEqualTo(NOW.plusMillis(5_000));
        verify(repository).save(job);
    }

    @Test
    void shouldMarkJobSucceededWhenBrokerageFillsImmediately() {
        StockOrderJob job = processingJob("order_1", 1);
        when(repository.findByJobId("order_1")).thenReturn(Optional.of(job));
        when(brokerageClient.submitOrder(job.toBrokerageOrderRequest()))
            .thenReturn(new BrokerageStartResult("br-1", BrokerageRemoteStatus.FILLED, 10, 0, new BigDecimal("69950"), null));

        jobProcessor.processClaimedJob("order_1");

        assertThat(job.getExternalOrderId()).isEqualTo("br-1");
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getExecutionStatus()).isEqualTo(BrokerageRemoteStatus.FILLED);
        assertThat(job.getAverageExecutedPrice()).isEqualByComparingTo("69950");
        assertThat(job.getCompletedAt()).isEqualTo(NOW);
        assertThat(job.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        verify(repository, atLeast(2)).save(job);
    }

    @Test
    void shouldRescheduleWhenBrokerageOrderRemainsPending() {
        StockOrderJob job = processingJob("order_1", 1);
        when(repository.findByJobId("order_1")).thenReturn(Optional.of(job));
        when(brokerageClient.submitOrder(job.toBrokerageOrderRequest()))
            .thenReturn(new BrokerageStartResult("br-1", BrokerageRemoteStatus.PENDING, 0, 10, null, null));

        jobProcessor.processClaimedJob("order_1");

        assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(job.getExternalOrderId()).isEqualTo("br-1");
        assertThat(job.getLeaseUntil()).isNull();
        assertThat(job.getNextAttemptAt()).isEqualTo(NOW.plusMillis(50));
    }

    @Test
    void shouldMarkRejectedOrderAsFailed() {
        StockOrderJob job = processingJob("order_1", 1);
        when(repository.findByJobId("order_1")).thenReturn(Optional.of(job));
        when(brokerageClient.submitOrder(job.toBrokerageOrderRequest()))
            .thenReturn(new BrokerageStartResult("br-1", BrokerageRemoteStatus.REJECTED, 0, 10, null, "Price band violation"));

        jobProcessor.processClaimedJob("order_1");

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorCode()).isEqualTo(JobFailureCode.BROKERAGE_ORDER_REJECTED.name());
        assertThat(job.getErrorMessage()).isEqualTo("Price band violation");
    }

    @Test
    void shouldRetryOnRetryableBrokerageFailure() {
        StockOrderJob job = processingJob("order_1", 1);
        when(repository.findByJobId("order_1")).thenReturn(Optional.of(job));
        when(brokerageClient.submitOrder(job.toBrokerageOrderRequest()))
            .thenThrow(new BrokerageClientException(JobFailureCode.BROKERAGE_TIMEOUT, true, "Brokerage timed out"));

        jobProcessor.processClaimedJob("order_1");

        assertThat(job.getStatus()).isEqualTo(JobStatus.RETRY_SCHEDULED);
        assertThat(job.getErrorCode()).isEqualTo(JobFailureCode.BROKERAGE_TIMEOUT.name());
        assertThat(job.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void shouldIgnoreMissingJobs() {
        when(repository.findByJobId("missing")).thenReturn(Optional.empty());

        jobProcessor.processClaimedJob("missing");

        verifyNoInteractions(brokerageClient);
    }

    @Test
    void shouldNotClaimLeasedProcessingJob() {
        StockOrderJob job = processingJob("order_1", 1);
        job.setExternalOrderId("br-1");
        when(repository.findByJobId("order_1")).thenReturn(Optional.of(job));

        boolean claimed = jobProcessor.claimJobForProcessing("order_1");

        assertThat(claimed).isFalse();
        verify(repository, never()).save(job);
    }

    private StockOrderJob queuedJob(String jobId, Instant nextAttemptAt) {
        return StockOrderJob.queued(
            jobId,
            "idem-" + jobId,
            "hash-" + jobId,
            "KIS",
            "12345678-01",
            "005930",
            BrokerageOrderSide.BUY,
            BrokerageOrderType.LIMIT,
            10,
            new BigDecimal("70000"),
            nextAttemptAt
        );
    }

    private StockOrderJob processingJob(String jobId, int attemptCount) {
        StockOrderJob job = queuedJob(jobId, NOW.minusSeconds(1));
        job.setStatus(JobStatus.PROCESSING);
        job.setAttemptCount(attemptCount);
        job.setLeaseUntil(NOW.plusSeconds(30));
        job.setNextAttemptAt(NOW.minusSeconds(1));
        return job;
    }

    private PlatformTransactionManager testTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}

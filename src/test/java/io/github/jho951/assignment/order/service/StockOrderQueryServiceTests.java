package io.github.jho951.assignment.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.jho951.assignment.brokerage.BrokerageRemoteStatus;
import io.github.jho951.assignment.order.domain.BrokerageOrderSide;
import io.github.jho951.assignment.order.domain.BrokerageOrderType;
import io.github.jho951.assignment.order.domain.JobFailureCode;
import io.github.jho951.assignment.order.domain.JobStatus;
import io.github.jho951.assignment.order.domain.StockOrderJob;
import io.github.jho951.assignment.order.repository.StockOrderJobRepository;
import io.github.jho951.assignment.order.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class StockOrderQueryServiceTests {

    private static final Instant NOW = Instant.parse("2026-05-07T06:00:00Z");

    @Mock
    private StockOrderJobRepository repository;

    private StockOrderQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new StockOrderQueryService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldReturnJobStatus() {
        StockOrderJob job = succeededJob();
        when(repository.findByJobId("order_1")).thenReturn(Optional.of(job));

        var response = queryService.getJobStatus("order_1");

        assertThat(response.jobId()).isEqualTo("order_1");
        assertThat(response.accountNumberMasked()).endsWith("8-01");
        assertThat(response.executionStatus()).isEqualTo("FILLED");
    }

    @Test
    void shouldRejectResultBeforeTerminalStatus() {
        StockOrderJob job = StockOrderJob.queued(
            "order_1",
            "idem-1",
            "hash-1",
            "KIS",
            "12345678-01",
            "005930",
            BrokerageOrderSide.BUY,
            BrokerageOrderType.LIMIT,
            10,
            new BigDecimal("70000"),
            NOW
        );
        when(repository.findByJobId("order_1")).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> queryService.getJobResult("order_1"))
            .isInstanceOf(ApiException.class)
            .satisfies(exception ->
                assertThat(((ApiException) exception).getCode()).isEqualTo(JobFailureCode.RESULT_NOT_READY)
            );
    }

    @Test
    void shouldHideExpiredJobs() {
        StockOrderJob job = succeededJob();
        job.setExpiresAt(NOW.minusSeconds(1));
        when(repository.findByJobId("order_1")).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> queryService.getJobStatus("order_1"))
            .isInstanceOf(ApiException.class)
            .satisfies(exception ->
                assertThat(((ApiException) exception).getCode()).isEqualTo(JobFailureCode.JOB_NOT_FOUND)
            );
    }

    @Test
    void shouldListVisibleJobs() {
        StockOrderJob job = succeededJob();
        when(repository.findVisibleJobs(org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.eq(NOW), org.mockito.ArgumentMatchers.any(PageRequest.class)))
            .thenReturn(new PageImpl<>(java.util.List.of(job), PageRequest.of(0, 20), 1));

        var response = queryService.listJobs(0, 20, null);

        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).symbol()).isEqualTo("005930");
    }

    private StockOrderJob succeededJob() {
        StockOrderJob job = StockOrderJob.queued(
            "order_1",
            "idem-1",
            "hash-1",
            "KIS",
            "12345678-01",
            "005930",
            BrokerageOrderSide.BUY,
            BrokerageOrderType.LIMIT,
            10,
            new BigDecimal("70000"),
            NOW
        );
        job.setStatus(JobStatus.PROCESSING);
        job.setExternalOrderId("br-1");
        job.setExecutionStatus(BrokerageRemoteStatus.FILLED);
        job.setFilledQuantity(10);
        job.setRemainingQuantity(0);
        job.setAverageExecutedPrice(new BigDecimal("69950"));
        job.setStatus(JobStatus.SUCCEEDED);
        job.setCompletedAt(NOW);
        job.setExpiresAt(NOW.plusSeconds(60));
        return job;
    }
}

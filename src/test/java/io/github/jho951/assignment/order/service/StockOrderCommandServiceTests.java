package io.github.jho951.assignment.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.jho951.assignment.order.domain.BrokerageOrderSide;
import io.github.jho951.assignment.order.domain.BrokerageOrderType;
import io.github.jho951.assignment.order.domain.JobFailureCode;
import io.github.jho951.assignment.order.domain.JobStatus;
import io.github.jho951.assignment.order.domain.StockOrderJob;
import io.github.jho951.assignment.order.repository.StockOrderJobRepository;
import io.github.jho951.assignment.order.web.ApiException;
import io.github.jho951.assignment.order.web.dto.StockOrderCreateRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockOrderCommandServiceTests {

    private static final Instant NOW = Instant.parse("2026-05-07T06:00:00Z");

    @Mock
    private StockOrderJobRepository repository;

    private RequestHashService requestHashService;

    private StockOrderCommandService stockOrderCommandService;

    @BeforeEach
    void setUp() {
        requestHashService = new RequestHashService();
        stockOrderCommandService = new StockOrderCommandService(repository, requestHashService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldCreateNewJob() {
        StockOrderCreateRequest request = request();
        when(repository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any(StockOrderJob.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        StockOrderCommandService.CreateJobResult result = stockOrderCommandService.createJob("idem-1", request);

        assertThat(result.replayed()).isFalse();
        assertThat(result.stockOrderJob().getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(result.stockOrderJob().getBrokerageCode()).isEqualTo("KIS");
        assertThat(result.stockOrderJob().getCreatedAt()).isNull();
    }

    @Test
    void shouldReplayExistingJobForSameIdempotencyKeyAndRequest() {
        StockOrderCreateRequest request = request();
        StockOrderJob existing = StockOrderJob.queued(
            "order_1",
            "idem-1",
            requestHashService.hashOrderRequest(request),
            "KIS",
            "12345678-01",
            "005930",
            BrokerageOrderSide.BUY,
            BrokerageOrderType.LIMIT,
            10,
            new BigDecimal("70000"),
            NOW
        );
        when(repository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(existing));

        StockOrderCommandService.CreateJobResult result = stockOrderCommandService.createJob("idem-1", request);

        assertThat(result.replayed()).isTrue();
        assertThat(result.stockOrderJob()).isSameAs(existing);
    }

    @Test
    void shouldRejectConflictingIdempotencyKeyReuse() {
        StockOrderCreateRequest request = request();
        StockOrderJob existing = StockOrderJob.queued(
            "order_1",
            "idem-1",
            requestHashService.hashOrderRequest(request),
            "KIS",
            "12345678-01",
            "005930",
            BrokerageOrderSide.BUY,
            BrokerageOrderType.LIMIT,
            10,
            new BigDecimal("70000"),
            NOW
        );
        when(repository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(existing));

        StockOrderCreateRequest changed = new StockOrderCreateRequest(
            "KIS",
            "12345678-01",
            "000660",
            BrokerageOrderSide.BUY,
            BrokerageOrderType.LIMIT,
            10,
            new BigDecimal("70000")
        );

        assertThatThrownBy(() -> stockOrderCommandService.createJob("idem-1", changed))
            .isInstanceOf(ApiException.class)
            .satisfies(exception ->
                assertThat(((ApiException) exception).getCode()).isEqualTo(JobFailureCode.IDEMPOTENCY_KEY_CONFLICT)
            );
    }

    @Test
    void shouldRejectMissingIdempotencyKey() {
        assertThatThrownBy(() -> stockOrderCommandService.createJob(null, request()))
            .isInstanceOf(ApiException.class)
            .satisfies(exception ->
                assertThat(((ApiException) exception).getCode()).isEqualTo(JobFailureCode.MISSING_IDEMPOTENCY_KEY)
            );
    }

    private StockOrderCreateRequest request() {
        return new StockOrderCreateRequest(
            "KIS",
            "12345678-01",
            "005930",
            BrokerageOrderSide.BUY,
            BrokerageOrderType.LIMIT,
            10,
            new BigDecimal("70000")
        );
    }
}

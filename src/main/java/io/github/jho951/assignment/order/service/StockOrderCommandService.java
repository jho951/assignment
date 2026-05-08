package io.github.jho951.assignment.order.service;

import io.github.jho951.assignment.order.domain.JobFailureCode;
import io.github.jho951.assignment.order.domain.StockOrderJob;
import io.github.jho951.assignment.order.repository.StockOrderJobRepository;
import io.github.jho951.assignment.order.web.ApiException;
import io.github.jho951.assignment.order.web.dto.StockOrderCreateRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class StockOrderCommandService {

    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;
    private static final String IDEMPOTENCY_KEY_PATTERN = "^[A-Za-z0-9._-]+$";
    private static final String INVALID_IDEMPOTENCY_KEY_MESSAGE =
        "Idempotency-Key must be 1-128 characters of letters, digits, dot, underscore, or hyphen";

    private final StockOrderJobRepository stockOrderJobRepository;
    private final RequestHashService requestHashService;
    private final Clock clock;

    public StockOrderCommandService(
        StockOrderJobRepository stockOrderJobRepository,
        RequestHashService requestHashService,
        Clock clock
    ) {
        this.stockOrderJobRepository = stockOrderJobRepository;
        this.requestHashService = requestHashService;
        this.clock = clock;
    }

    public CreateJobResult createJob(String idempotencyKey, StockOrderCreateRequest request) {
        if (idempotencyKey == null) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                JobFailureCode.MISSING_IDEMPOTENCY_KEY,
                "Idempotency-Key header is required"
            );
        }

        String normalizedKey = idempotencyKey.trim();
        validateIdempotencyKey(normalizedKey);
        String requestHash = requestHashService.hashOrderRequest(request);

        return stockOrderJobRepository.findByIdempotencyKey(normalizedKey)
            .map(job -> replayOrConflict(job, requestHash))
            .orElseGet(() -> saveNewJob(normalizedKey, requestHash, request));
    }

    private CreateJobResult replayOrConflict(StockOrderJob job, String requestHash) {
        if (!job.getRequestHash().equals(requestHash)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                JobFailureCode.IDEMPOTENCY_KEY_CONFLICT,
                "The same Idempotency-Key was used with a different request body"
            );
        }
        return new CreateJobResult(job, true);
    }

    private CreateJobResult saveNewJob(String normalizedKey, String requestHash, StockOrderCreateRequest request) {
        Instant now = Instant.now(clock);
        StockOrderJob newJob = StockOrderJob.queued(
            generateJobId(),
            normalizedKey,
            requestHash,
            request.brokerageCode(),
            request.accountNumber(),
            request.symbol(),
            request.side(),
            request.orderType(),
            request.quantity(),
            request.price(),
            now
        );

        try {
            return new CreateJobResult(stockOrderJobRepository.save(newJob), false);
        } catch (DataIntegrityViolationException exception) {
            StockOrderJob existingJob = stockOrderJobRepository.findByIdempotencyKey(normalizedKey)
                .orElseThrow(() -> exception);
            if (!existingJob.getRequestHash().equals(requestHash)) {
                throw new ApiException(
                    HttpStatus.CONFLICT,
                    JobFailureCode.IDEMPOTENCY_KEY_CONFLICT,
                    "The same Idempotency-Key was used with a different request body"
                );
            }
            return new CreateJobResult(existingJob, true);
        }
    }

    private String generateJobId() {
        return "order_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey.isEmpty()
            || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH
            || !idempotencyKey.matches(IDEMPOTENCY_KEY_PATTERN)
        ) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                JobFailureCode.INVALID_IDEMPOTENCY_KEY,
                INVALID_IDEMPOTENCY_KEY_MESSAGE
            );
        }
    }

    public record CreateJobResult(StockOrderJob stockOrderJob, boolean replayed) {
    }
}

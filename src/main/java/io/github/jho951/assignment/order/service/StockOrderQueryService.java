package io.github.jho951.assignment.order.service;

import io.github.jho951.assignment.order.domain.JobFailureCode;
import io.github.jho951.assignment.order.domain.JobStatus;
import io.github.jho951.assignment.order.domain.StockOrderJob;
import io.github.jho951.assignment.order.repository.StockOrderJobRepository;
import io.github.jho951.assignment.order.web.ApiException;
import io.github.jho951.assignment.order.web.dto.StockOrderErrorResponse;
import io.github.jho951.assignment.order.web.dto.StockOrderListResponse;
import io.github.jho951.assignment.order.web.dto.StockOrderResultResponse;
import io.github.jho951.assignment.order.web.dto.StockOrderStatusResponse;
import io.github.jho951.assignment.order.web.dto.StockOrderSummaryResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StockOrderQueryService {

    private static final List<JobStatus> TERMINAL_STATUSES = List.of(JobStatus.SUCCEEDED, JobStatus.FAILED);
    private static final Sort DEFAULT_SORT = Sort.by(
        Sort.Order.desc("createdAt"),
        Sort.Order.desc("jobId")
    );

    private final StockOrderJobRepository stockOrderJobRepository;
    private final Clock clock;

    public StockOrderQueryService(StockOrderJobRepository stockOrderJobRepository, Clock clock) {
        this.stockOrderJobRepository = stockOrderJobRepository;
        this.clock = clock;
    }

    public StockOrderStatusResponse getJobStatus(String jobId) {
        StockOrderJob job = findJob(jobId, Instant.now(clock));
        return new StockOrderStatusResponse(
            job.getJobId(),
            job.getStatus().name(),
            job.getBrokerageCode(),
            maskAccountNumber(job.getAccountNumber()),
            job.getSymbol(),
            job.getSide().name(),
            job.getOrderType().name(),
            job.getQuantity(),
            job.getPrice(),
            job.getAttemptCount(),
            job.getExternalOrderId(),
            job.getExecutionStatus() == null ? null : job.getExecutionStatus().name(),
            job.getFilledQuantity(),
            job.getRemainingQuantity(),
            job.getAverageExecutedPrice(),
            job.getCreatedAt(),
            job.getUpdatedAt(),
            job.getCompletedAt(),
            job.getExpiresAt(),
            mapError(job)
        );
    }

    public StockOrderResultResponse getJobResult(String jobId) {
        StockOrderJob job = findJob(jobId, Instant.now(clock));
        if (!job.getStatus().isTerminal()) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                JobFailureCode.RESULT_NOT_READY,
                "The result is not ready yet"
            );
        }
        return new StockOrderResultResponse(
            job.getJobId(),
            job.getStatus().name(),
            job.getBrokerageCode(),
            maskAccountNumber(job.getAccountNumber()),
            job.getSymbol(),
            job.getSide().name(),
            job.getOrderType().name(),
            job.getQuantity(),
            job.getPrice(),
            job.getExternalOrderId(),
            job.getExecutionStatus() == null ? null : job.getExecutionStatus().name(),
            job.getFilledQuantity(),
            job.getRemainingQuantity(),
            job.getAverageExecutedPrice(),
            job.getCompletedAt(),
            job.getExpiresAt(),
            mapError(job)
        );
    }

    public StockOrderListResponse listJobs(int page, int size, JobStatus status) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(normalizedPage, normalizedSize, DEFAULT_SORT);
        Instant now = Instant.now(clock);

        var jobs = status == null
            ? stockOrderJobRepository.findVisibleJobs(TERMINAL_STATUSES, now, pageable)
            : stockOrderJobRepository.findVisibleJobsByStatus(status, TERMINAL_STATUSES, now, pageable);

        List<StockOrderSummaryResponse> items = jobs.getContent().stream()
            .map(this::toSummaryResponse)
            .toList();

        return new StockOrderListResponse(
            jobs.getNumber(),
            jobs.getSize(),
            jobs.getTotalElements(),
            jobs.getTotalPages(),
            items
        );
    }

    private StockOrderSummaryResponse toSummaryResponse(StockOrderJob job) {
        return new StockOrderSummaryResponse(
            job.getJobId(),
            job.getStatus().name(),
            job.getBrokerageCode(),
            maskAccountNumber(job.getAccountNumber()),
            job.getSymbol(),
            job.getSide().name(),
            job.getOrderType().name(),
            job.getQuantity(),
            job.getPrice(),
            job.getAttemptCount(),
            job.getExternalOrderId(),
            job.getExecutionStatus() == null ? null : job.getExecutionStatus().name(),
            job.getFilledQuantity(),
            job.getRemainingQuantity(),
            job.getAverageExecutedPrice(),
            job.getCreatedAt(),
            job.getUpdatedAt(),
            job.getCompletedAt(),
            job.getExpiresAt(),
            mapError(job)
        );
    }

    private StockOrderJob findJob(String jobId, Instant now) {
        StockOrderJob job = stockOrderJobRepository.findByJobId(jobId)
            .orElseThrow(this::notFound);
        if (isExpired(job, now)) {
            throw notFound();
        }
        return job;
    }

    private boolean isExpired(StockOrderJob job, Instant now) {
        return job.isTerminal() && job.getExpiresAt() != null && !job.getExpiresAt().isAfter(now);
    }

    private ApiException notFound() {
        return new ApiException(
            HttpStatus.NOT_FOUND,
            JobFailureCode.JOB_NOT_FOUND,
            "Stock order job not found"
        );
    }

    private StockOrderErrorResponse mapError(StockOrderJob job) {
        return job.getErrorCode() == null ? null : new StockOrderErrorResponse(job.getErrorCode(), job.getErrorMessage());
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return accountNumber;
        }
        return "*".repeat(accountNumber.length() - 4) + accountNumber.substring(accountNumber.length() - 4);
    }
}

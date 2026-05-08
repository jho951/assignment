package io.github.jho951.assignment.order.web;

import io.github.jho951.assignment.order.domain.JobStatus;
import io.github.jho951.assignment.order.service.StockOrderCommandService;
import io.github.jho951.assignment.order.service.StockOrderQueryService;
import io.github.jho951.assignment.order.web.dto.StockOrderCreateRequest;
import io.github.jho951.assignment.order.web.dto.StockOrderCreateResponse;
import io.github.jho951.assignment.order.web.dto.StockOrderListResponse;
import io.github.jho951.assignment.order.web.dto.StockOrderResultResponse;
import io.github.jho951.assignment.order.web.dto.StockOrderStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock-orders")
public class StockOrderController {

    private final StockOrderQueryService stockOrderQueryService;
    private final StockOrderCommandService stockOrderCommandService;

    public StockOrderController(
        StockOrderQueryService stockOrderQueryService,
        StockOrderCommandService stockOrderCommandService
    ) {
        this.stockOrderQueryService = stockOrderQueryService;
        this.stockOrderCommandService = stockOrderCommandService;
    }

    @PostMapping
    public ResponseEntity<StockOrderCreateResponse> createJob(
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody StockOrderCreateRequest request
    ) {
        StockOrderCommandService.CreateJobResult result = stockOrderCommandService.createJob(idempotencyKey, request);
        StockOrderCreateResponse response = new StockOrderCreateResponse(
            result.stockOrderJob().getJobId(),
            result.stockOrderJob().getStatus().name(),
            result.stockOrderJob().getCreatedAt()
        );
        return result.replayed() ? ResponseEntity.ok(response) : ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{jobId}")
    public StockOrderStatusResponse getJobStatus(@PathVariable String jobId) {
        return stockOrderQueryService.getJobStatus(jobId);
    }

    @GetMapping("/{jobId}/result")
    public StockOrderResultResponse getJobResult(@PathVariable String jobId) {
        return stockOrderQueryService.getJobResult(jobId);
    }

    @GetMapping
    public StockOrderListResponse listJobs(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) JobStatus status
    ) {
        return stockOrderQueryService.listJobs(page, size, status);
    }
}

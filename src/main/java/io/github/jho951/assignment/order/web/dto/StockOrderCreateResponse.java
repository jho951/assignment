package io.github.jho951.assignment.order.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record StockOrderCreateResponse(
    @Schema(description = "Server generated order job identifier", example = "order_01HZY6J2K6K6M3VY7R4G2T0K9A")
    String jobId,

    @Schema(description = "Current job status", example = "QUEUED")
    String status,

    @Schema(description = "Job creation timestamp", example = "2026-05-07T06:00:00Z")
    Instant createdAt
) {
}

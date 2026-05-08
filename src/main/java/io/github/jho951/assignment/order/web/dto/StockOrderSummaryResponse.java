package io.github.jho951.assignment.order.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record StockOrderSummaryResponse(
    String jobId,
    String status,
    String brokerageCode,
    String accountNumberMasked,
    String symbol,
    String side,
    String orderType,
    int quantity,
    BigDecimal price,
    int attemptCount,
    String brokerageOrderId,
    String executionStatus,
    int filledQuantity,
    int remainingQuantity,
    BigDecimal averageExecutedPrice,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt,
    Instant expiresAt,
    StockOrderErrorResponse error
) {
}

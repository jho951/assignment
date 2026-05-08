package io.github.jho951.assignment.order.web.dto;

import java.util.List;

public record StockOrderListResponse(
    int page,
    int size,
    long totalElements,
    int totalPages,
    List<StockOrderSummaryResponse> items
) {
}

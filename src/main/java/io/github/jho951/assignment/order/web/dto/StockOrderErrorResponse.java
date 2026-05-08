package io.github.jho951.assignment.order.web.dto;

public record StockOrderErrorResponse(
    String code,
    String message
) {
}

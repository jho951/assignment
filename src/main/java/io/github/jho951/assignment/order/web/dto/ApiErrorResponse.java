package io.github.jho951.assignment.order.web.dto;

public record ApiErrorResponse(
    String code,
    String message
) {
}

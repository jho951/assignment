package io.github.jho951.assignment.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jho951.assignment.order.domain.JobFailureCode;
import io.github.jho951.assignment.order.web.dto.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class StockOrderExceptionHandlerTests {

    private final StockOrderExceptionHandler handler = new StockOrderExceptionHandler();

    @Test
    void shouldMapApiException() {
        var response = handler.handleApiException(
            new ApiException(HttpStatus.CONFLICT, JobFailureCode.IDEMPOTENCY_KEY_CONFLICT, "conflict")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse("IDEMPOTENCY_KEY_CONFLICT", "conflict"));
    }

    @Test
    void shouldMapUnexpectedException() {
        var response = handler.handleUnexpected(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse("INTERNAL_ERROR", "Unexpected internal error"));
    }
}

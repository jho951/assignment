package io.github.jho951.assignment.job.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import io.github.jho951.assignment.job.domain.JobFailureCode;
import io.github.jho951.assignment.job.web.dto.ApiErrorResponse;
import io.github.jho951.assignment.job.web.dto.ImageJobCreateRequest;
import jakarta.validation.Valid;

class ImageJobExceptionHandlerTests {

    private final ImageJobExceptionHandler handler = new ImageJobExceptionHandler();

    @Test
    void shouldMapApiExceptionToConfiguredStatusAndErrorCode() {
        ApiException exception = new ApiException(
                HttpStatus.CONFLICT,
                JobFailureCode.IDEMPOTENCY_KEY_CONFLICT,
                "The same Idempotency-Key was used with a different request body"
        );

        ResponseEntity<ApiErrorResponse> response = handler.handleApiException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse(
                JobFailureCode.IDEMPOTENCY_KEY_CONFLICT.name(),
                "The same Idempotency-Key was used with a different request body"
        ));
    }

    @Test
    void shouldMapValidationErrorToInvalidRequestUsingFirstFieldMessage() throws Exception {
        MethodArgumentNotValidException exception = validationExceptionWithFirstFieldError("imageUrl is required");

        ResponseEntity<ApiErrorResponse> response = handler.handleValidation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse(
                JobFailureCode.INVALID_REQUEST.name(),
                "imageUrl is required"
        ));
    }

    @Test
    void shouldUseDefaultValidationMessageWhenNoFieldErrorExists() throws Exception {
        Method method = ValidationFixture.class.getDeclaredMethod("handle", ImageJobCreateRequest.class);
        MethodParameter methodParameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(
                new ImageJobCreateRequest(null),
                "request"
        );
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<ApiErrorResponse> response = handler.handleValidation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse(
                JobFailureCode.INVALID_REQUEST.name(),
                "Invalid request"
        ));
    }

    @Test
    void shouldMapUnexpectedExceptionToInternalError() {
        ResponseEntity<ApiErrorResponse> response = handler.handleUnexpected(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse(
                JobFailureCode.INTERNAL_ERROR.name(),
                "Unexpected internal error"
        ));
    }

    private MethodArgumentNotValidException validationExceptionWithFirstFieldError(String message) throws Exception {
        Method method = ValidationFixture.class.getDeclaredMethod("handle", ImageJobCreateRequest.class);
        MethodParameter methodParameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(
                new ImageJobCreateRequest(null),
                "request"
        );
        bindingResult.addError(new FieldError("request", "imageUrl", message));
        return new MethodArgumentNotValidException(methodParameter, bindingResult);
    }

    private static final class ValidationFixture {

        @SuppressWarnings("unused")
        void handle(@Valid ImageJobCreateRequest request) {
        }
    }
}

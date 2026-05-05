package io.github.jho951.assignment.job.web

import io.github.jho951.assignment.job.domain.JobFailureCode
import io.github.jho951.assignment.job.web.dto.ApiErrorResponse
import io.github.jho951.assignment.job.web.dto.ImageJobCreateRequest
import jakarta.validation.Valid
import java.lang.reflect.Method
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException

class ImageJobExceptionHandlerTests {

    private val handler = ImageJobExceptionHandler()

    @Test
    fun shouldMapApiExceptionToConfiguredStatusAndErrorCode() {
        val exception = ApiException(
            HttpStatus.CONFLICT,
            JobFailureCode.IDEMPOTENCY_KEY_CONFLICT,
            "The same Idempotency-Key was used with a different request body"
        )

        val response: ResponseEntity<ApiErrorResponse> = handler.handleApiException(exception)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body).isEqualTo(
            ApiErrorResponse(
                JobFailureCode.IDEMPOTENCY_KEY_CONFLICT.name,
                "The same Idempotency-Key was used with a different request body"
            )
        )
    }

    @Test
    fun shouldMapValidationErrorToInvalidRequestUsingFirstFieldMessage() {
        val exception = validationExceptionWithFirstFieldError("imageUrl is required")

        val response = handler.handleValidation(exception)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).isEqualTo(
            ApiErrorResponse(
                JobFailureCode.INVALID_REQUEST.name,
                "imageUrl is required"
            )
        )
    }

    @Test
    fun shouldUseDefaultValidationMessageWhenNoFieldErrorExists() {
        val method: Method = ValidationFixture::class.java.getDeclaredMethod("handle", ImageJobCreateRequest::class.java)
        val methodParameter = MethodParameter(method, 0)
        val bindingResult = BeanPropertyBindingResult(
            ImageJobCreateRequest(null),
            "request"
        )
        val exception = MethodArgumentNotValidException(methodParameter, bindingResult)

        val response = handler.handleValidation(exception)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).isEqualTo(
            ApiErrorResponse(
                JobFailureCode.INVALID_REQUEST.name,
                "Invalid request"
            )
        )
    }

    @Test
    fun shouldMapUnexpectedExceptionToInternalError() {
        val response = handler.handleUnexpected(IllegalStateException("boom"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.body).isEqualTo(
            ApiErrorResponse(
                JobFailureCode.INTERNAL_ERROR.name,
                "Unexpected internal error"
            )
        )
    }

    private fun validationExceptionWithFirstFieldError(message: String): MethodArgumentNotValidException {
        val method: Method = ValidationFixture::class.java.getDeclaredMethod("handle", ImageJobCreateRequest::class.java)
        val methodParameter = MethodParameter(method, 0)
        val bindingResult = BeanPropertyBindingResult(
            ImageJobCreateRequest(null),
            "request"
        )
        bindingResult.addError(FieldError("request", "imageUrl", message))
        return MethodArgumentNotValidException(methodParameter, bindingResult)
    }

    private class ValidationFixture {

        @Suppress("unused")
        fun handle(@Valid request: ImageJobCreateRequest) {
        }
    }
}

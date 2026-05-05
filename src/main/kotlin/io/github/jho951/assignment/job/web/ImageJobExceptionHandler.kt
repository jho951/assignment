package io.github.jho951.assignment.job.web

import io.github.jho951.assignment.job.domain.JobFailureCode
import io.github.jho951.assignment.job.web.dto.ApiErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ImageJobExceptionHandler {

    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(exception.httpStatus)
            .body(ApiErrorResponse(exception.code.name, exception.message ?: "Unexpected internal error"))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(exception: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> {
        val message = exception.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "Invalid request"
        return ResponseEntity.badRequest()
            .body(ApiErrorResponse(JobFailureCode.INVALID_REQUEST.name, message))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiErrorResponse(JobFailureCode.INTERNAL_ERROR.name, "Unexpected internal error"))
}

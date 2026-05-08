package io.github.jho951.assignment.order.web;

import io.github.jho951.assignment.order.domain.JobFailureCode;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final JobFailureCode code;

    public ApiException(HttpStatus httpStatus, JobFailureCode code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public JobFailureCode getCode() {
        return code;
    }
}

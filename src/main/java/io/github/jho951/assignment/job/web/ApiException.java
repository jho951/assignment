package io.github.jho951.assignment.job.web;

import org.springframework.http.HttpStatus;

import io.github.jho951.assignment.job.domain.JobFailureCode;
import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final JobFailureCode code;

    public ApiException(HttpStatus httpStatus, JobFailureCode code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }
}

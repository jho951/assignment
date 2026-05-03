package io.github.jho951.assignment.job.worker;

import io.github.jho951.assignment.job.domain.JobFailureCode;

public class WorkerClientException extends RuntimeException {

    private final JobFailureCode failureCode;
    private final boolean retryable;

    public WorkerClientException(JobFailureCode failureCode, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.failureCode = failureCode;
        this.retryable = retryable;
    }

    public WorkerClientException(JobFailureCode failureCode, boolean retryable, String message) {
        super(message);
        this.failureCode = failureCode;
        this.retryable = retryable;
    }

    public JobFailureCode getFailureCode() {
        return failureCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}

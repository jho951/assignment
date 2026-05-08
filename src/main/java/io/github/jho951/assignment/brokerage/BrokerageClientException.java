package io.github.jho951.assignment.brokerage;

import io.github.jho951.assignment.order.domain.JobFailureCode;

public class BrokerageClientException extends RuntimeException {

    private final JobFailureCode failureCode;
    private final boolean retryable;

    public BrokerageClientException(JobFailureCode failureCode, boolean retryable, String message) {
        super(message);
        this.failureCode = failureCode;
        this.retryable = retryable;
    }

    public BrokerageClientException(JobFailureCode failureCode, boolean retryable, String message, Throwable cause) {
        super(message, cause);
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

package io.github.jho951.assignment.order.domain;

public enum JobStatus {
    QUEUED,
    PROCESSING,
    RETRY_SCHEDULED,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}

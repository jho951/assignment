package io.github.jho951.assignment.job.domain

enum class JobStatus {
    QUEUED,
    PROCESSING,
    RETRY_SCHEDULED,
    SUCCEEDED,
    FAILED;

    fun isTerminal(): Boolean = this == SUCCEEDED || this == FAILED
}

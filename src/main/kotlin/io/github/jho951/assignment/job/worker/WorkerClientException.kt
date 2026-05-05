package io.github.jho951.assignment.job.worker

import io.github.jho951.assignment.job.domain.JobFailureCode

open class WorkerClientException(
    val failureCode: JobFailureCode,
    private val retryable: Boolean,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {

    constructor(
        failureCode: JobFailureCode,
        retryable: Boolean,
        message: String
    ) : this(failureCode, retryable, message, null)

    fun isRetryable(): Boolean = retryable
}

package io.github.jho951.assignment.job.worker

import io.github.jho951.assignment.job.domain.JobFailureCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkerClientExceptionTests {

    @Test
    fun shouldExposeFailureCodeRetryableFlagAndCause() {
        val cause = RuntimeException("socket closed")

        val exception = WorkerClientException(
            JobFailureCode.WORKER_UNAVAILABLE,
            true,
            "worker unavailable",
            cause
        )

        assertThat(exception.failureCode).isEqualTo(JobFailureCode.WORKER_UNAVAILABLE)
        assertThat(exception.isRetryable()).isTrue()
        assertThat(exception).hasMessage("worker unavailable")
        assertThat(exception.cause).isSameAs(cause)
    }
}

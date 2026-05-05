package io.github.jho951.assignment.job.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.jho951.assignment.job.domain.JobFailureCode;

class WorkerClientExceptionTests {

    @Test
    void shouldExposeFailureCodeRetryableFlagAndCause() {
        RuntimeException cause = new RuntimeException("socket closed");

        WorkerClientException exception = new WorkerClientException(
                JobFailureCode.WORKER_UNAVAILABLE,
                true,
                "worker unavailable",
                cause
        );

        assertThat(exception.getFailureCode()).isEqualTo(JobFailureCode.WORKER_UNAVAILABLE);
        assertThat(exception.isRetryable()).isTrue();
        assertThat(exception).hasMessage("worker unavailable");
        assertThat(exception.getCause()).isSameAs(cause);
    }
}

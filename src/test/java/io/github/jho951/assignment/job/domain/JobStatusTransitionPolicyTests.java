package io.github.jho951.assignment.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JobStatusTransitionPolicyTests {

    private final JobStatusTransitionPolicy policy = new JobStatusTransitionPolicy();

    @Test
    void shouldAllowDocumentedTransitions() {
        assertThat(policy.canTransition(JobStatus.QUEUED, JobStatus.PROCESSING)).isTrue();
        assertThat(policy.canTransition(JobStatus.PROCESSING, JobStatus.RETRY_SCHEDULED)).isTrue();
        assertThat(policy.canTransition(JobStatus.PROCESSING, JobStatus.SUCCEEDED)).isTrue();
        assertThat(policy.canTransition(JobStatus.RETRY_SCHEDULED, JobStatus.PROCESSING)).isTrue();
        assertThat(policy.canTransition(JobStatus.RETRY_SCHEDULED, JobStatus.FAILED)).isTrue();
    }

    @Test
    void shouldRejectUndocumentedTransitions() {
        assertThat(policy.canTransition(JobStatus.QUEUED, JobStatus.SUCCEEDED)).isFalse();
        assertThat(policy.canTransition(JobStatus.SUCCEEDED, JobStatus.PROCESSING)).isFalse();
        assertThat(policy.canTransition(JobStatus.FAILED, JobStatus.RETRY_SCHEDULED)).isFalse();
    }

    @Test
    void shouldThrowForInvalidTransitionAssertion() {
        assertThatThrownBy(() -> policy.assertTransition(JobStatus.QUEUED, JobStatus.FAILED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUEUED -> FAILED");
    }
}

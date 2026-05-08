package io.github.jho951.assignment.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JobStatusTransitionPolicyTests {

    private final JobStatusTransitionPolicy policy = new JobStatusTransitionPolicy();

    @Test
    void shouldAllowDefinedTransitions() {
        assertThat(policy.canTransition(JobStatus.QUEUED, JobStatus.PROCESSING)).isTrue();
        assertThat(policy.canTransition(JobStatus.PROCESSING, JobStatus.SUCCEEDED)).isTrue();
        assertThat(policy.canTransition(JobStatus.PROCESSING, JobStatus.RETRY_SCHEDULED)).isTrue();
        assertThat(policy.canTransition(JobStatus.RETRY_SCHEDULED, JobStatus.FAILED)).isTrue();
    }

    @Test
    void shouldRejectUndefinedTransitions() {
        assertThat(policy.canTransition(JobStatus.QUEUED, JobStatus.SUCCEEDED)).isFalse();
        assertThat(policy.canTransition(JobStatus.FAILED, JobStatus.PROCESSING)).isFalse();
    }

    @Test
    void shouldThrowWhenTransitionIsInvalid() {
        assertThatThrownBy(() -> policy.assertTransition(JobStatus.QUEUED, JobStatus.FAILED))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("QUEUED -> FAILED");
    }
}

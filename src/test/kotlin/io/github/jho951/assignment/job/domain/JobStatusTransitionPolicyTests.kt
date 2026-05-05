package io.github.jho951.assignment.job.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JobStatusTransitionPolicyTests {

    private val policy = JobStatusTransitionPolicy()

    @Test
    fun shouldAllowDocumentedTransitions() {
        assertThat(policy.canTransition(JobStatus.QUEUED, JobStatus.PROCESSING)).isTrue()
        assertThat(policy.canTransition(JobStatus.PROCESSING, JobStatus.RETRY_SCHEDULED)).isTrue()
        assertThat(policy.canTransition(JobStatus.PROCESSING, JobStatus.SUCCEEDED)).isTrue()
        assertThat(policy.canTransition(JobStatus.RETRY_SCHEDULED, JobStatus.PROCESSING)).isTrue()
        assertThat(policy.canTransition(JobStatus.RETRY_SCHEDULED, JobStatus.FAILED)).isTrue()
    }

    @Test
    fun shouldRejectUndocumentedTransitions() {
        assertThat(policy.canTransition(JobStatus.QUEUED, JobStatus.SUCCEEDED)).isFalse()
        assertThat(policy.canTransition(JobStatus.SUCCEEDED, JobStatus.PROCESSING)).isFalse()
        assertThat(policy.canTransition(JobStatus.FAILED, JobStatus.RETRY_SCHEDULED)).isFalse()
    }

    @Test
    fun shouldThrowForInvalidTransitionAssertion() {
        assertThatThrownBy { policy.assertTransition(JobStatus.QUEUED, JobStatus.FAILED) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("QUEUED -> FAILED")
    }
}

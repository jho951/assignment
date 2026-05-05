package io.github.jho951.assignment.job.domain

import java.util.EnumMap
import java.util.EnumSet
import org.springframework.stereotype.Component

@Component
class JobStatusTransitionPolicy {

    private val allowedTransitions = EnumMap<JobStatus, Set<JobStatus>>(JobStatus::class.java).apply {
        put(JobStatus.QUEUED, EnumSet.of(JobStatus.PROCESSING))
        put(JobStatus.PROCESSING, EnumSet.of(JobStatus.SUCCEEDED, JobStatus.RETRY_SCHEDULED, JobStatus.FAILED))
        put(JobStatus.RETRY_SCHEDULED, EnumSet.of(JobStatus.PROCESSING, JobStatus.FAILED))
        put(JobStatus.SUCCEEDED, EnumSet.noneOf(JobStatus::class.java))
        put(JobStatus.FAILED, EnumSet.noneOf(JobStatus::class.java))
    }

    fun canTransition(from: JobStatus, to: JobStatus): Boolean =
        allowedTransitions.getOrDefault(from, EnumSet.noneOf(JobStatus::class.java)).contains(to)

    fun assertTransition(from: JobStatus, to: JobStatus) {
        if (!canTransition(from, to)) {
            throw IllegalStateException("Invalid job status transition: $from -> $to")
        }
    }
}

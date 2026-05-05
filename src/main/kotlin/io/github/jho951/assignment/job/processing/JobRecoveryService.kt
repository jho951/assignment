package io.github.jho951.assignment.job.processing

import io.github.jho951.assignment.config.JobProperties
import io.github.jho951.assignment.job.domain.JobFailureCode
import io.github.jho951.assignment.job.domain.JobStatus
import io.github.jho951.assignment.job.domain.JobStatusTransitionPolicy
import io.github.jho951.assignment.job.repository.ImageJobRepository
import java.time.Clock
import java.time.Instant
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class JobRecoveryService(
    private val imageJobRepository: ImageJobRepository,
    private val transitionPolicy: JobStatusTransitionPolicy,
    private val jobProcessor: JobProcessor,
    private val jobProperties: JobProperties,
    private val clock: Clock
) {

    @Transactional
    fun recoverStaleJobs() {
        val now = Instant.now(clock)
        val staleJobs = imageJobRepository.findStaleProcessingJobs(
            JobStatus.PROCESSING,
            now,
            PageRequest.of(0, jobProperties.batchSize)
        )

        for (imageJob in staleJobs) {
            if (imageJob.attemptCount < jobProperties.maxAttempts) {
                transitionPolicy.assertTransition(JobStatus.PROCESSING, JobStatus.RETRY_SCHEDULED)
                imageJob.status = JobStatus.RETRY_SCHEDULED
                imageJob.errorCode = JobFailureCode.WORKER_UNAVAILABLE.name
                imageJob.errorMessage = "Recovered stale processing job"
                imageJob.leaseUntil = null
                imageJob.nextAttemptAt = now.plus(jobProcessor.calculateBackoff(imageJob.attemptCount))
                continue
            }

            transitionPolicy.assertTransition(JobStatus.PROCESSING, JobStatus.FAILED)
            imageJob.status = JobStatus.FAILED
            imageJob.errorCode = JobFailureCode.MAX_ATTEMPTS_EXCEEDED.name
            imageJob.errorMessage = "Maximum retry attempts exceeded during stale job recovery"
            imageJob.leaseUntil = null
            imageJob.completedAt = now
            imageJob.expiresAt = now.plusSeconds(7 * 24 * 60 * 60L)
        }
    }
}

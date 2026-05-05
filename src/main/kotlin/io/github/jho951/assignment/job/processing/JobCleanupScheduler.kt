package io.github.jho951.assignment.job.processing

import io.github.jho951.assignment.config.JobProperties
import io.github.jho951.assignment.job.domain.JobStatus
import io.github.jho951.assignment.job.repository.ImageJobRepository
import java.time.Clock
import java.time.Instant
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@ConditionalOnProperty(name = ["jobs.scheduling-enabled"], havingValue = "true", matchIfMissing = true)
class JobCleanupScheduler(
    private val imageJobRepository: ImageJobRepository,
    private val jobProperties: JobProperties,
    private val clock: Clock
) {

    @Transactional
    @Scheduled(fixedDelayString = "\${jobs.cleanup-interval-ms}")
    fun cleanupExpiredJobs() {
        val expiredJobs = imageJobRepository.findExpiredJobs(
            listOf(JobStatus.SUCCEEDED, JobStatus.FAILED),
            Instant.now(clock),
            PageRequest.of(0, jobProperties.batchSize)
        )

        if (expiredJobs.isNotEmpty()) {
            imageJobRepository.deleteAllInBatch(expiredJobs)
        }
    }
}

package io.github.jho951.assignment.job.processing

import java.util.concurrent.Executor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["jobs.scheduling-enabled"], havingValue = "true", matchIfMissing = true)
class JobProcessingScheduler(
    private val jobProcessor: JobProcessor,
    private val jobRecoveryService: JobRecoveryService,
    private val jobTaskExecutor: Executor
) {

    @Scheduled(fixedDelayString = "\${jobs.poll-interval-ms}")
    fun processDueJobs() {
        jobRecoveryService.recoverStaleJobs()

        for (jobId in jobProcessor.findDueJobIds()) {
            if (jobProcessor.claimJobForProcessing(jobId)) {
                jobTaskExecutor.execute { jobProcessor.processClaimedJob(jobId) }
            }
        }
    }
}

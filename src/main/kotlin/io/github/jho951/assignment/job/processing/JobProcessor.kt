package io.github.jho951.assignment.job.processing

import io.github.jho951.assignment.config.JobProperties
import io.github.jho951.assignment.job.domain.ImageJob
import io.github.jho951.assignment.job.domain.JobFailureCode
import io.github.jho951.assignment.job.domain.JobStatus
import io.github.jho951.assignment.job.domain.JobStatusTransitionPolicy
import io.github.jho951.assignment.job.repository.ImageJobRepository
import io.github.jho951.assignment.job.worker.WorkerClient
import io.github.jho951.assignment.job.worker.WorkerClientException
import io.github.jho951.assignment.job.worker.WorkerRemoteStatus
import io.github.jho951.assignment.job.worker.WorkerStatusResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class JobProcessor(
    private val imageJobRepository: ImageJobRepository,
    private val transitionPolicy: JobStatusTransitionPolicy,
    private val workerClient: WorkerClient,
    private val jobProperties: JobProperties,
    private val clock: Clock,
    platformTransactionManager: PlatformTransactionManager
) {

    private val transactionTemplate = TransactionTemplate(platformTransactionManager)

    fun findDueJobIds(): List<String> =
        imageJobRepository.findDueJobs(
            listOf(JobStatus.QUEUED, JobStatus.RETRY_SCHEDULED, JobStatus.PROCESSING),
            Instant.now(clock),
            PageRequest.of(0, jobProperties.batchSize)
        ).map { it.jobId }

    fun claimJobForProcessing(jobId: String): Boolean {
        val now = Instant.now(clock)
        return transactionTemplate.execute { _ ->
            val imageJob = imageJobRepository.findByJobId(jobId).orElse(null) ?: return@execute false
            val nextAttemptAt = imageJob.nextAttemptAt
            if (nextAttemptAt != null && nextAttemptAt.isAfter(now)) {
                return@execute false
            }

            if (imageJob.status == JobStatus.QUEUED || imageJob.status == JobStatus.RETRY_SCHEDULED) {
                transitionPolicy.assertTransition(imageJob.status, JobStatus.PROCESSING)
                imageJob.status = JobStatus.PROCESSING
            } else if (imageJob.status == JobStatus.PROCESSING) {
                if (imageJob.leaseUntil != null || imageJob.externalJobId == null) {
                    return@execute false
                }
            } else {
                return@execute false
            }

            imageJob.attemptCount = imageJob.attemptCount + 1
            imageJob.leaseUntil = now.plusMillis(jobProperties.leaseTimeoutMs)
            imageJob.nextAttemptAt = now.plusMillis(jobProperties.leaseTimeoutMs)
            imageJob.errorCode = null
            imageJob.errorMessage = null
            imageJobRepository.save(imageJob)
            true
        } == true
    }

    fun processClaimedJob(jobId: String) {
        try {
            val imageJob = loadJob(jobId)
            if (imageJob == null || imageJob.status != JobStatus.PROCESSING) {
                return
            }

            if (Thread.currentThread().isInterrupted) {
                handleInterruptedExecution(jobId)
                return
            }

            if (imageJob.externalJobId == null) {
                val startResult = workerClient.startProcess(imageJob.imageUrl)
                if (startResult.status == WorkerRemoteStatus.COMPLETED || startResult.status == WorkerRemoteStatus.FAILED) {
                    recordWorkerJobId(jobId, startResult.workerJobId)
                    completeFromWorkerStatus(workerClient.getProcessStatus(startResult.workerJobId), jobId)
                    return
                }

                rescheduleProcessingPoll(jobId, startResult.workerJobId)
                return
            }

            val externalJobId = imageJob.externalJobId ?: return
            val statusResult = workerClient.getProcessStatus(externalJobId)
            if (statusResult.status == WorkerRemoteStatus.PROCESSING) {
                rescheduleProcessingPoll(jobId, externalJobId)
                return
            }

            completeFromWorkerStatus(statusResult, jobId)
        } catch (exception: WorkerClientException) {
            handleWorkerFailure(jobId, exception)
        } catch (exception: Exception) {
            handleUnexpectedFailure(jobId, exception)
        }
    }

    fun calculateBackoff(attemptCount: Int): Duration =
        when (attemptCount) {
            1 -> Duration.ofSeconds(2)
            2 -> Duration.ofSeconds(10)
            else -> Duration.ofSeconds(30)
        }

    fun markSucceeded(imageJob: ImageJob, result: String?) {
        transitionPolicy.assertTransition(imageJob.status, JobStatus.SUCCEEDED)
        val now = Instant.now(clock)
        imageJob.status = JobStatus.SUCCEEDED
        imageJob.result = result
        imageJob.errorCode = null
        imageJob.errorMessage = null
        imageJob.leaseUntil = null
        imageJob.completedAt = now
        imageJob.expiresAt = now.plus(RESULT_RETENTION)
        imageJobRepository.save(imageJob)
    }

    fun markFailed(imageJob: ImageJob, code: JobFailureCode, message: String?) {
        transitionPolicy.assertTransition(imageJob.status, JobStatus.FAILED)
        val now = Instant.now(clock)
        imageJob.status = JobStatus.FAILED
        imageJob.errorCode = code.name
        imageJob.errorMessage = message
        imageJob.leaseUntil = null
        imageJob.completedAt = now
        imageJob.expiresAt = now.plus(RESULT_RETENTION)
        imageJobRepository.save(imageJob)
    }

    private fun completeFromWorkerStatus(statusResult: WorkerStatusResult, jobId: String) {
        if (statusResult.status == WorkerRemoteStatus.COMPLETED) {
            markSucceeded(jobId, statusResult.result)
            return
        }

        markFailed(jobId, JobFailureCode.INTERNAL_ERROR, "Mock Worker reported FAILED")
    }

    private fun recordWorkerJobId(jobId: String, workerJobId: String) {
        transactionTemplate.executeWithoutResult {
            val imageJob = requireJob(jobId)
            imageJob.externalJobId = workerJobId
            imageJobRepository.save(imageJob)
        }
    }

    private fun rescheduleProcessingPoll(jobId: String, workerJobId: String) {
        transactionTemplate.executeWithoutResult {
            val imageJob = requireJob(jobId)
            if (imageJob.status == JobStatus.PROCESSING) {
                imageJob.externalJobId = workerJobId
                imageJob.leaseUntil = null
                imageJob.nextAttemptAt = Instant.now(clock).plusMillis(maxOf(jobProperties.pollIntervalMs, 1L))
                imageJobRepository.save(imageJob)
            }
        }
    }

    private fun handleWorkerFailure(jobId: String, exception: WorkerClientException) {
        transactionTemplate.executeWithoutResult {
            val imageJob = requireJob(jobId)
            if (imageJob.status != JobStatus.PROCESSING) {
                return@executeWithoutResult
            }

            if (exception.isRetryable() && imageJob.attemptCount < jobProperties.maxAttempts) {
                transitionPolicy.assertTransition(JobStatus.PROCESSING, JobStatus.RETRY_SCHEDULED)
                imageJob.status = JobStatus.RETRY_SCHEDULED
                imageJob.errorCode = exception.failureCode.name
                imageJob.errorMessage = exception.message
                imageJob.leaseUntil = null
                imageJob.nextAttemptAt = Instant.now(clock).plus(calculateBackoff(imageJob.attemptCount))
                imageJobRepository.save(imageJob)
                return@executeWithoutResult
            }

            if (exception.isRetryable()) {
                markFailed(imageJob, JobFailureCode.MAX_ATTEMPTS_EXCEEDED, "Maximum retry attempts exceeded")
                return@executeWithoutResult
            }

            markFailed(imageJob, exception.failureCode, exception.message)
        }
    }

    private fun handleUnexpectedFailure(jobId: String, exception: Exception) {
        transactionTemplate.executeWithoutResult {
            val imageJob = requireJob(jobId)
            if (imageJob.status == JobStatus.PROCESSING) {
                markFailed(
                    imageJob,
                    JobFailureCode.INTERNAL_ERROR,
                    exception.message ?: "Unexpected internal error"
                )
            }
        }
    }

    private fun handleInterruptedExecution(jobId: String) {
        transactionTemplate.executeWithoutResult {
            val imageJob = requireJob(jobId)
            if (imageJob.status != JobStatus.PROCESSING) {
                return@executeWithoutResult
            }

            if (imageJob.attemptCount < jobProperties.maxAttempts) {
                transitionPolicy.assertTransition(JobStatus.PROCESSING, JobStatus.RETRY_SCHEDULED)
                imageJob.status = JobStatus.RETRY_SCHEDULED
                imageJob.errorCode = JobFailureCode.INTERNAL_ERROR.name
                imageJob.errorMessage = "Job polling thread was interrupted before completion"
                imageJob.leaseUntil = null
                imageJob.nextAttemptAt = Instant.now(clock).plus(calculateBackoff(imageJob.attemptCount))
                imageJobRepository.save(imageJob)
                return@executeWithoutResult
            }

            markFailed(imageJob, JobFailureCode.MAX_ATTEMPTS_EXCEEDED, "Maximum retry attempts exceeded")
        }
    }

    private fun markSucceeded(jobId: String, result: String?) {
        transactionTemplate.executeWithoutResult {
            val imageJob = requireJob(jobId)
            markSucceeded(imageJob, result)
        }
    }

    private fun markFailed(jobId: String, code: JobFailureCode, message: String?) {
        transactionTemplate.executeWithoutResult {
            val imageJob = requireJob(jobId)
            markFailed(imageJob, code, message)
        }
    }

    private fun loadJob(jobId: String): ImageJob? =
        imageJobRepository.findByJobId(jobId).orElse(null)

    private fun requireJob(jobId: String): ImageJob =
        imageJobRepository.findByJobId(jobId)
            .orElseThrow { IllegalStateException("Image job not found: $jobId") }

    private companion object {
        val RESULT_RETENTION: Duration = Duration.ofDays(7)
    }
}

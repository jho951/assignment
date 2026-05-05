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
import io.github.jho951.assignment.job.worker.WorkerStartResult
import io.github.jho951.assignment.job.worker.WorkerStatusResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus

@ExtendWith(MockitoExtension::class)
class JobProcessorTests {

    @Mock
    private lateinit var imageJobRepository: ImageJobRepository

    @Mock
    private lateinit var workerClient: WorkerClient

    private lateinit var jobProcessor: JobProcessor

    @BeforeEach
    fun setUp() {
        jobProcessor = JobProcessor(
            imageJobRepository,
            JobStatusTransitionPolicy(),
            workerClient,
            JobProperties(
                3,
                50,
                20,
                5_000,
                60_000,
                false,
                JobProperties.Executor(1, 1, 1)
            ),
            Clock.fixed(NOW, ZoneOffset.UTC),
            testTransactionManager()
        )
    }

    @Test
    fun shouldFindDueJobIdsUsingConfiguredStatusesAndBatchSize() {
        val queuedJob = queuedJob("job-1", NOW.minusSeconds(1))
        val retryJob = queuedJob("job-2", NOW.minusSeconds(2))
        retryJob.status = JobStatus.RETRY_SCHEDULED

        whenever(
            imageJobRepository.findDueJobs(
                listOf(JobStatus.QUEUED, JobStatus.RETRY_SCHEDULED, JobStatus.PROCESSING),
                NOW,
                PageRequest.of(0, 20)
            )
        )
            .thenReturn(listOf(queuedJob, retryJob))

        val dueJobIds = jobProcessor.findDueJobIds()

        assertThat(dueJobIds).containsExactly("job-1", "job-2")

        verify(imageJobRepository).findDueJobs(
            listOf(JobStatus.QUEUED, JobStatus.RETRY_SCHEDULED, JobStatus.PROCESSING),
            NOW,
            PageRequest.of(0, 20)
        )
    }

    @Test
    fun shouldClaimQueuedJobForProcessing() {
        val imageJob = queuedJob("job-claim", NOW.minusSeconds(1))
        whenever(imageJobRepository.findByJobId("job-claim")).thenReturn(Optional.of(imageJob))

        val claimed = jobProcessor.claimJobForProcessing("job-claim")

        assertThat(claimed).isTrue()
        assertThat(imageJob.status).isEqualTo(JobStatus.PROCESSING)
        assertThat(imageJob.attemptCount).isEqualTo(1)
        assertThat(imageJob.leaseUntil).isEqualTo(NOW.plusMillis(5_000))
        assertThat(imageJob.nextAttemptAt).isEqualTo(NOW.plusMillis(5_000))
        verify(imageJobRepository).save(imageJob)
    }

    @Test
    fun shouldClaimRetryScheduledJobForProcessing() {
        val imageJob = queuedJob("job-retry", NOW.minusSeconds(1))
        imageJob.status = JobStatus.RETRY_SCHEDULED
        imageJob.attemptCount = 1
        whenever(imageJobRepository.findByJobId("job-retry")).thenReturn(Optional.of(imageJob))

        val claimed = jobProcessor.claimJobForProcessing("job-retry")

        assertThat(claimed).isTrue()
        assertThat(imageJob.status).isEqualTo(JobStatus.PROCESSING)
        assertThat(imageJob.attemptCount).isEqualTo(2)
        assertThat(imageJob.nextAttemptAt).isEqualTo(NOW.plusMillis(5_000))
        verify(imageJobRepository).save(imageJob)
    }

    @Test
    fun shouldClaimProcessingJobForSingleStepContinuationWhenLeaseIsReleased() {
        val imageJob = processingJob("job-processing-continue", 1)
        imageJob.leaseUntil = null
        imageJob.externalJobId = "worker-continue"
        whenever(imageJobRepository.findByJobId("job-processing-continue")).thenReturn(Optional.of(imageJob))

        val claimed = jobProcessor.claimJobForProcessing("job-processing-continue")

        assertThat(claimed).isTrue()
        assertThat(imageJob.status).isEqualTo(JobStatus.PROCESSING)
        assertThat(imageJob.attemptCount).isEqualTo(2)
        assertThat(imageJob.leaseUntil).isEqualTo(NOW.plusMillis(5_000))
        assertThat(imageJob.nextAttemptAt).isEqualTo(NOW.plusMillis(5_000))
    }

    @Test
    fun shouldNotClaimProcessingJobWhenLeaseIsStillHeld() {
        val imageJob = processingJob("job-processing-leased", 1)
        imageJob.externalJobId = "worker-leased"
        whenever(imageJobRepository.findByJobId("job-processing-leased")).thenReturn(Optional.of(imageJob))

        val claimed = jobProcessor.claimJobForProcessing("job-processing-leased")

        assertThat(claimed).isFalse()
        verify(imageJobRepository, never()).save(imageJob)
    }

    @Test
    fun shouldNotClaimJobWhenNextAttemptIsInFuture() {
        val imageJob = queuedJob("job-future", NOW.plusSeconds(30))
        whenever(imageJobRepository.findByJobId("job-future")).thenReturn(Optional.of(imageJob))

        val claimed = jobProcessor.claimJobForProcessing("job-future")

        assertThat(claimed).isFalse()
        assertThat(imageJob.status).isEqualTo(JobStatus.QUEUED)
        verify(imageJobRepository, never()).save(imageJob)
    }

    @Test
    fun shouldIgnoreClaimedJobWhenItIsMissingOrNotProcessing() {
        whenever(imageJobRepository.findByJobId("missing-job")).thenReturn(Optional.empty())

        jobProcessor.processClaimedJob("missing-job")

        verifyNoInteractions(workerClient)
    }

    @Test
    fun shouldMarkJobSucceededWhenWorkerCompletesImmediately() {
        val imageJob = processingJob("job-success", 1)
        whenever(imageJobRepository.findByJobId("job-success")).thenReturn(Optional.of(imageJob))
        whenever(workerClient.startProcess(imageJob.imageUrl))
            .thenReturn(WorkerStartResult("worker-1", WorkerRemoteStatus.COMPLETED))
        whenever(workerClient.getProcessStatus("worker-1"))
            .thenReturn(WorkerStatusResult("worker-1", WorkerRemoteStatus.COMPLETED, "https://cdn.example/result.png"))

        jobProcessor.processClaimedJob("job-success")

        assertThat(imageJob.externalJobId).isEqualTo("worker-1")
        assertThat(imageJob.status).isEqualTo(JobStatus.SUCCEEDED)
        assertThat(imageJob.result).isEqualTo("https://cdn.example/result.png")
        assertThat(imageJob.errorCode).isNull()
        assertThat(imageJob.errorMessage).isNull()
        assertThat(imageJob.leaseUntil).isNull()
        assertThat(imageJob.completedAt).isEqualTo(NOW)
        assertThat(imageJob.expiresAt).isEqualTo(NOW.plus(Duration.ofDays(7)))
        verify(imageJobRepository, atLeast(2)).save(imageJob)
    }

    @Test
    fun shouldMarkJobFailedWhenWorkerReportsFailed() {
        val imageJob = processingJob("job-failed", 1)
        whenever(imageJobRepository.findByJobId("job-failed")).thenReturn(Optional.of(imageJob))
        whenever(workerClient.startProcess(imageJob.imageUrl))
            .thenReturn(WorkerStartResult("worker-2", WorkerRemoteStatus.COMPLETED))
        whenever(workerClient.getProcessStatus("worker-2"))
            .thenReturn(WorkerStatusResult("worker-2", WorkerRemoteStatus.FAILED, null))

        jobProcessor.processClaimedJob("job-failed")

        assertThat(imageJob.externalJobId).isEqualTo("worker-2")
        assertThat(imageJob.status).isEqualTo(JobStatus.FAILED)
        assertThat(imageJob.errorCode).isEqualTo(JobFailureCode.INTERNAL_ERROR.name)
        assertThat(imageJob.errorMessage).isEqualTo("Mock Worker reported FAILED")
        assertThat(imageJob.leaseUntil).isNull()
        assertThat(imageJob.completedAt).isEqualTo(NOW)
        assertThat(imageJob.expiresAt).isEqualTo(NOW.plus(Duration.ofDays(7)))
    }

    @Test
    fun shouldKeepProcessingStatusAndRescheduleWhenWorkerStillProcessingAfterStart() {
        val imageJob = processingJob("job-still-processing-start", 1)
        whenever(imageJobRepository.findByJobId("job-still-processing-start")).thenReturn(Optional.of(imageJob))
        whenever(workerClient.startProcess(imageJob.imageUrl))
            .thenReturn(WorkerStartResult("worker-processing", WorkerRemoteStatus.PROCESSING))

        jobProcessor.processClaimedJob("job-still-processing-start")

        assertThat(imageJob.status).isEqualTo(JobStatus.PROCESSING)
        assertThat(imageJob.externalJobId).isEqualTo("worker-processing")
        assertThat(imageJob.leaseUntil).isNull()
        assertThat(imageJob.nextAttemptAt).isEqualTo(NOW.plusMillis(50))
        assertThat(imageJob.completedAt).isNull()
        verify(workerClient, never()).getProcessStatus("worker-processing")
    }

    @Test
    fun shouldKeepProcessingStatusAndRescheduleWhenExistingWorkerJobIsStillProcessing() {
        val imageJob = processingJob("job-still-processing-poll", 2)
        imageJob.externalJobId = "worker-existing"
        whenever(imageJobRepository.findByJobId("job-still-processing-poll")).thenReturn(Optional.of(imageJob))
        whenever(workerClient.getProcessStatus("worker-existing"))
            .thenReturn(WorkerStatusResult("worker-existing", WorkerRemoteStatus.PROCESSING, null))

        jobProcessor.processClaimedJob("job-still-processing-poll")

        assertThat(imageJob.status).isEqualTo(JobStatus.PROCESSING)
        assertThat(imageJob.leaseUntil).isNull()
        assertThat(imageJob.nextAttemptAt).isEqualTo(NOW.plusMillis(50))
        assertThat(imageJob.completedAt).isNull()
    }

    @Test
    fun shouldScheduleRetryForRetryableWorkerFailure() {
        val imageJob = processingJob("job-retryable", 1)
        whenever(imageJobRepository.findByJobId("job-retryable")).thenReturn(Optional.of(imageJob))
        whenever(workerClient.startProcess(imageJob.imageUrl))
            .thenThrow(WorkerClientException(JobFailureCode.WORKER_TIMEOUT, true, "Worker timed out"))

        jobProcessor.processClaimedJob("job-retryable")

        assertThat(imageJob.status).isEqualTo(JobStatus.RETRY_SCHEDULED)
        assertThat(imageJob.errorCode).isEqualTo(JobFailureCode.WORKER_TIMEOUT.name)
        assertThat(imageJob.errorMessage).isEqualTo("Worker timed out")
        assertThat(imageJob.leaseUntil).isNull()
        assertThat(imageJob.nextAttemptAt).isEqualTo(NOW.plusSeconds(2))
    }

    @Test
    fun shouldFailWhenRetryableWorkerFailureExceedsMaxAttempts() {
        val imageJob = processingJob("job-max-attempts", 3)
        whenever(imageJobRepository.findByJobId("job-max-attempts")).thenReturn(Optional.of(imageJob))
        whenever(workerClient.startProcess(imageJob.imageUrl))
            .thenThrow(WorkerClientException(JobFailureCode.WORKER_TIMEOUT, true, "Worker timed out"))

        jobProcessor.processClaimedJob("job-max-attempts")

        assertThat(imageJob.status).isEqualTo(JobStatus.FAILED)
        assertThat(imageJob.errorCode).isEqualTo(JobFailureCode.MAX_ATTEMPTS_EXCEEDED.name)
        assertThat(imageJob.errorMessage).isEqualTo("Maximum retry attempts exceeded")
        assertThat(imageJob.completedAt).isEqualTo(NOW)
        assertThat(imageJob.expiresAt).isEqualTo(NOW.plus(Duration.ofDays(7)))
    }

    @Test
    fun shouldFailWhenWorkerFailureIsNotRetryable() {
        val imageJob = processingJob("job-bad-request", 1)
        whenever(imageJobRepository.findByJobId("job-bad-request")).thenReturn(Optional.of(imageJob))
        whenever(workerClient.startProcess(imageJob.imageUrl))
            .thenThrow(WorkerClientException(JobFailureCode.WORKER_BAD_REQUEST, false, "Bad request"))

        jobProcessor.processClaimedJob("job-bad-request")

        assertThat(imageJob.status).isEqualTo(JobStatus.FAILED)
        assertThat(imageJob.errorCode).isEqualTo(JobFailureCode.WORKER_BAD_REQUEST.name)
        assertThat(imageJob.errorMessage).isEqualTo("Bad request")
    }

    @Test
    fun shouldFailClaimedJobWhenPollingThreadIsInterrupted() {
        val imageJob = processingJob("job-interrupted", 1)
        imageJob.externalJobId = "worker-3"
        whenever(imageJobRepository.findByJobId("job-interrupted")).thenReturn(Optional.of(imageJob))

        try {
            Thread.currentThread().interrupt()

            jobProcessor.processClaimedJob("job-interrupted")

            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(imageJob.status).isEqualTo(JobStatus.RETRY_SCHEDULED)
            assertThat(imageJob.errorCode).isEqualTo(JobFailureCode.INTERNAL_ERROR.name)
            assertThat(imageJob.errorMessage).contains("interrupted")
            assertThat(imageJob.nextAttemptAt).isEqualTo(NOW.plusSeconds(2))
            assertThat(imageJob.leaseUntil).isNull()
            verifyNoInteractions(workerClient)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun shouldCalculateBackoffByAttemptCount() {
        assertThat(jobProcessor.calculateBackoff(1)).isEqualTo(Duration.ofSeconds(2))
        assertThat(jobProcessor.calculateBackoff(2)).isEqualTo(Duration.ofSeconds(10))
        assertThat(jobProcessor.calculateBackoff(3)).isEqualTo(Duration.ofSeconds(30))
        assertThat(jobProcessor.calculateBackoff(99)).isEqualTo(Duration.ofSeconds(30))
    }

    private fun queuedJob(jobId: String, nextAttemptAt: Instant): ImageJob =
        ImageJob.queued(
            jobId,
            "idem-$jobId",
            "hash-$jobId",
            "https://example.com/$jobId.png",
            nextAttemptAt
        )

    private fun processingJob(jobId: String, attemptCount: Int): ImageJob {
        val imageJob = queuedJob(jobId, NOW.minusSeconds(1))
        imageJob.status = JobStatus.PROCESSING
        imageJob.attemptCount = attemptCount
        imageJob.leaseUntil = NOW.plusSeconds(30)
        return imageJob
    }

    private fun testTransactionManager(): PlatformTransactionManager =
        object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

            override fun commit(status: TransactionStatus) {
            }

            override fun rollback(status: TransactionStatus) {
            }
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-04T06:00:00Z")
    }
}

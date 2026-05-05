package io.github.jho951.assignment.job.processing

import java.util.concurrent.Executor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class JobProcessingSchedulerTests {

    @Mock
    private lateinit var jobProcessor: JobProcessor

    @Mock
    private lateinit var jobRecoveryService: JobRecoveryService

    @Mock
    private lateinit var jobTaskExecutor: Executor

    private lateinit var jobProcessingScheduler: JobProcessingScheduler

    @BeforeEach
    fun setUp() {
        jobProcessingScheduler = JobProcessingScheduler(jobProcessor, jobRecoveryService, jobTaskExecutor)
    }

    @Test
    fun shouldRecoverClaimAndDispatchOnlyClaimedJobs() {
        whenever(jobProcessor.findDueJobIds()).thenReturn(listOf("job-1", "job-2"))
        whenever(jobProcessor.claimJobForProcessing("job-1")).thenReturn(true)
        whenever(jobProcessor.claimJobForProcessing("job-2")).thenReturn(false)

        jobProcessingScheduler.processDueJobs()

        val inOrder: InOrder = inOrder(jobRecoveryService, jobProcessor, jobTaskExecutor)
        inOrder.verify(jobRecoveryService).recoverStaleJobs()
        inOrder.verify(jobProcessor).findDueJobIds()
        inOrder.verify(jobProcessor).claimJobForProcessing("job-1")
        inOrder.verify(jobTaskExecutor).execute(any(Runnable::class.java))
        inOrder.verify(jobProcessor).claimJobForProcessing("job-2")

        val captor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(jobTaskExecutor).execute(captor.capture())
        captor.value.run()
        verify(jobProcessor).processClaimedJob("job-1")
        verify(jobProcessor, never()).processClaimedJob("job-2")
    }
}

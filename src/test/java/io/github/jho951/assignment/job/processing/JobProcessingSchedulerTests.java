package io.github.jho951.assignment.job.processing;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JobProcessingSchedulerTests {

    @Mock
    private JobProcessor jobProcessor;

    @Mock
    private JobRecoveryService jobRecoveryService;

    @Mock
    private Executor jobTaskExecutor;

    private JobProcessingScheduler jobProcessingScheduler;

    @BeforeEach
    void setUp() {
        jobProcessingScheduler = new JobProcessingScheduler(jobProcessor, jobRecoveryService, jobTaskExecutor);
    }

    @Test
    void shouldRecoverClaimAndDispatchOnlyClaimedJobs() {
        when(jobProcessor.findDueJobIds()).thenReturn(List.of("job-1", "job-2"));
        when(jobProcessor.claimJobForProcessing("job-1")).thenReturn(true);
        when(jobProcessor.claimJobForProcessing("job-2")).thenReturn(false);

        jobProcessingScheduler.processDueJobs();

        InOrder inOrder = inOrder(jobRecoveryService, jobProcessor, jobTaskExecutor);
        inOrder.verify(jobRecoveryService).recoverStaleJobs();
        inOrder.verify(jobProcessor).findDueJobIds();
        inOrder.verify(jobProcessor).claimJobForProcessing("job-1");
        inOrder.verify(jobTaskExecutor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        inOrder.verify(jobProcessor).claimJobForProcessing("job-2");

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(jobTaskExecutor).execute(captor.capture());
        captor.getValue().run();
        verify(jobProcessor).processClaimedJob("job-1");
        verify(jobProcessor, never()).processClaimedJob("job-2");
    }
}

package io.github.jho951.assignment.order.processing;

import java.util.concurrent.Executor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "jobs.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class JobProcessingScheduler {

    private final JobProcessor jobProcessor;
    private final JobRecoveryService jobRecoveryService;
    private final Executor jobTaskExecutor;

    public JobProcessingScheduler(JobProcessor jobProcessor, JobRecoveryService jobRecoveryService, Executor jobTaskExecutor) {
        this.jobProcessor = jobProcessor;
        this.jobRecoveryService = jobRecoveryService;
        this.jobTaskExecutor = jobTaskExecutor;
    }

    @Scheduled(fixedDelayString = "${jobs.poll-interval-ms}")
    public void processDueJobs() {
        jobRecoveryService.recoverStaleJobs();

        for (String jobId : jobProcessor.findDueJobIds()) {
            if (jobProcessor.claimJobForProcessing(jobId)) {
                jobTaskExecutor.execute(() -> jobProcessor.processClaimedJob(jobId));
            }
        }
    }
}

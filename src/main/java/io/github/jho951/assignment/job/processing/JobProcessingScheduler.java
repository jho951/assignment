package io.github.jho951.assignment.job.processing;

import java.util.concurrent.Executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnProperty(name = "jobs.scheduling-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class JobProcessingScheduler {

    private final JobProcessor jobProcessor;
    private final JobRecoveryService jobRecoveryService;
    private final Executor jobTaskExecutor;

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

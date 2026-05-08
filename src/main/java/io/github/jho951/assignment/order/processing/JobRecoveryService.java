package io.github.jho951.assignment.order.processing;

import io.github.jho951.assignment.config.JobProperties;
import io.github.jho951.assignment.order.domain.JobFailureCode;
import io.github.jho951.assignment.order.domain.JobStatus;
import io.github.jho951.assignment.order.domain.JobStatusTransitionPolicy;
import io.github.jho951.assignment.order.repository.StockOrderJobRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobRecoveryService {

    private final StockOrderJobRepository stockOrderJobRepository;
    private final JobStatusTransitionPolicy transitionPolicy;
    private final JobProcessor jobProcessor;
    private final JobProperties jobProperties;
    private final Clock clock;

    public JobRecoveryService(
        StockOrderJobRepository stockOrderJobRepository,
        JobStatusTransitionPolicy transitionPolicy,
        JobProcessor jobProcessor,
        JobProperties jobProperties,
        Clock clock
    ) {
        this.stockOrderJobRepository = stockOrderJobRepository;
        this.transitionPolicy = transitionPolicy;
        this.jobProcessor = jobProcessor;
        this.jobProperties = jobProperties;
        this.clock = clock;
    }

    @Transactional
    public void recoverStaleJobs() {
        Instant now = Instant.now(clock);
        var staleJobs = stockOrderJobRepository.findStaleProcessingJobs(
            JobStatus.PROCESSING,
            now,
            PageRequest.of(0, jobProperties.batchSize())
        );

        for (var job : staleJobs) {
            if (job.getAttemptCount() < jobProperties.maxAttempts()) {
                transitionPolicy.assertTransition(JobStatus.PROCESSING, JobStatus.RETRY_SCHEDULED);
                job.setStatus(JobStatus.RETRY_SCHEDULED);
                job.setErrorCode(JobFailureCode.BROKERAGE_UNAVAILABLE.name());
                job.setErrorMessage("Recovered stale stock order job");
                job.setLeaseUntil(null);
                job.setNextAttemptAt(now.plus(jobProcessor.calculateBackoff(job.getAttemptCount())));
                continue;
            }

            transitionPolicy.assertTransition(JobStatus.PROCESSING, JobStatus.FAILED);
            job.setStatus(JobStatus.FAILED);
            job.setErrorCode(JobFailureCode.MAX_ATTEMPTS_EXCEEDED.name());
            job.setErrorMessage("Maximum retry attempts exceeded during stale job recovery");
            job.setLeaseUntil(null);
            job.setCompletedAt(now);
            job.setExpiresAt(now.plusSeconds(7L * 24L * 60L * 60L));
        }
    }
}

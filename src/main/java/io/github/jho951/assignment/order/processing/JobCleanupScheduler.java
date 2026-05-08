package io.github.jho951.assignment.order.processing;

import io.github.jho951.assignment.config.JobProperties;
import io.github.jho951.assignment.order.domain.JobStatus;
import io.github.jho951.assignment.order.repository.StockOrderJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "jobs.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class JobCleanupScheduler {

    private final StockOrderJobRepository stockOrderJobRepository;
    private final JobProperties jobProperties;
    private final Clock clock;

    public JobCleanupScheduler(StockOrderJobRepository stockOrderJobRepository, JobProperties jobProperties, Clock clock) {
        this.stockOrderJobRepository = stockOrderJobRepository;
        this.jobProperties = jobProperties;
        this.clock = clock;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${jobs.cleanup-interval-ms}")
    public void cleanupExpiredJobs() {
        var expiredJobs = stockOrderJobRepository.findExpiredJobs(
            List.of(JobStatus.SUCCEEDED, JobStatus.FAILED),
            Instant.now(clock),
            PageRequest.of(0, jobProperties.batchSize())
        );

        if (!expiredJobs.isEmpty()) {
            stockOrderJobRepository.deleteAllInBatch(expiredJobs);
        }
    }
}

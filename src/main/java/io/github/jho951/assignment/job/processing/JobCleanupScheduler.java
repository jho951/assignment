package io.github.jho951.assignment.job.processing;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.jho951.assignment.config.JobProperties;
import io.github.jho951.assignment.job.domain.ImageJob;
import io.github.jho951.assignment.job.domain.JobStatus;
import io.github.jho951.assignment.job.repository.ImageJobRepository;

@Component
@ConditionalOnProperty(name = "jobs.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class JobCleanupScheduler {

    private final ImageJobRepository imageJobRepository;
    private final JobProperties jobProperties;
    private final Clock clock;

    public JobCleanupScheduler(
            ImageJobRepository imageJobRepository,
            JobProperties jobProperties,
            Clock clock
    ) {
        this.imageJobRepository = imageJobRepository;
        this.jobProperties = jobProperties;
        this.clock = clock;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${jobs.cleanup-interval-ms}")
    public void cleanupExpiredJobs() {
        List<ImageJob> expiredJobs = imageJobRepository.findExpiredJobs(
                List.of(JobStatus.SUCCEEDED, JobStatus.FAILED),
                Instant.now(clock),
                PageRequest.of(0, jobProperties.batchSize())
        );

        if (!expiredJobs.isEmpty()) {
            imageJobRepository.deleteAllInBatch(expiredJobs);
        }
    }
}

package io.github.jho951.assignment.job.processing;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jho951.assignment.config.JobProperties;
import io.github.jho951.assignment.job.domain.ImageJob;
import io.github.jho951.assignment.job.domain.JobFailureCode;
import io.github.jho951.assignment.job.domain.JobStatus;
import io.github.jho951.assignment.job.domain.JobStatusTransitionPolicy;
import io.github.jho951.assignment.job.repository.ImageJobRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class JobRecoveryService {

    private final ImageJobRepository imageJobRepository;
    private final JobStatusTransitionPolicy transitionPolicy;
    private final JobProcessor jobProcessor;
    private final JobProperties jobProperties;
    private final Clock clock;

    @Transactional
    public void recoverStaleJobs() {
        Instant now = Instant.now(clock);
        List<ImageJob> staleJobs = imageJobRepository.findStaleProcessingJobs(
                JobStatus.PROCESSING,
                now,
                PageRequest.of(0, jobProperties.batchSize())
        );

        for (ImageJob imageJob : staleJobs) {
            if (imageJob.getAttemptCount() < jobProperties.maxAttempts()) {
                transitionPolicy.assertTransition(JobStatus.PROCESSING, JobStatus.RETRY_SCHEDULED);
                imageJob.setStatus(JobStatus.RETRY_SCHEDULED);
                imageJob.setErrorCode(JobFailureCode.WORKER_UNAVAILABLE.name());
                imageJob.setErrorMessage("Recovered stale processing job");
                imageJob.setLeaseUntil(null);
                imageJob.setNextAttemptAt(now.plus(jobProcessor.calculateBackoff(imageJob.getAttemptCount())));
                continue;
            }

            transitionPolicy.assertTransition(JobStatus.PROCESSING, JobStatus.FAILED);
            imageJob.setStatus(JobStatus.FAILED);
            imageJob.setErrorCode(JobFailureCode.MAX_ATTEMPTS_EXCEEDED.name());
            imageJob.setErrorMessage("Maximum retry attempts exceeded during stale job recovery");
            imageJob.setLeaseUntil(null);
            imageJob.setCompletedAt(now);
            imageJob.setExpiresAt(now.plusSeconds(7 * 24 * 60 * 60L));
        }
    }
}

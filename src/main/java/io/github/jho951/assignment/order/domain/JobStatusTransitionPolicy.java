package io.github.jho951.assignment.order.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class JobStatusTransitionPolicy {

    private final Map<JobStatus, Set<JobStatus>> allowedTransitions = new EnumMap<>(JobStatus.class);

    public JobStatusTransitionPolicy() {
        allowedTransitions.put(JobStatus.QUEUED, EnumSet.of(JobStatus.PROCESSING));
        allowedTransitions.put(JobStatus.PROCESSING, EnumSet.of(JobStatus.SUCCEEDED, JobStatus.RETRY_SCHEDULED, JobStatus.FAILED));
        allowedTransitions.put(JobStatus.RETRY_SCHEDULED, EnumSet.of(JobStatus.PROCESSING, JobStatus.FAILED));
        allowedTransitions.put(JobStatus.SUCCEEDED, EnumSet.noneOf(JobStatus.class));
        allowedTransitions.put(JobStatus.FAILED, EnumSet.noneOf(JobStatus.class));
    }

    public boolean canTransition(JobStatus from, JobStatus to) {
        return allowedTransitions.getOrDefault(from, EnumSet.noneOf(JobStatus.class)).contains(to);
    }

    public void assertTransition(JobStatus from, JobStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Invalid job status transition: " + from + " -> " + to);
        }
    }
}

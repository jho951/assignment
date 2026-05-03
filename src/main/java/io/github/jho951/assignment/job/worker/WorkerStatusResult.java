package io.github.jho951.assignment.job.worker;

public record WorkerStatusResult(
        String workerJobId,
        WorkerRemoteStatus status,
        String result
) {
}

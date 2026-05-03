package io.github.jho951.assignment.job.worker;

public record WorkerStartResult(
        String workerJobId,
        WorkerRemoteStatus status
) {}

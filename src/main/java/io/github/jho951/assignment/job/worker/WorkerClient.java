package io.github.jho951.assignment.job.worker;

public interface WorkerClient {

    WorkerStartResult startProcess(String imageUrl);

    WorkerStatusResult getProcessStatus(String workerJobId);
}

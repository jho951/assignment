package io.github.jho951.assignment.job.worker

interface WorkerClient {

    fun startProcess(imageUrl: String): WorkerStartResult

    fun getProcessStatus(workerJobId: String): WorkerStatusResult
}

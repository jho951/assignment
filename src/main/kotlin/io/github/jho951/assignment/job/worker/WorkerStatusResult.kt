package io.github.jho951.assignment.job.worker

import kotlin.jvm.JvmRecord

@JvmRecord
data class WorkerStatusResult(
    val workerJobId: String,
    val status: WorkerRemoteStatus,
    val result: String?
)

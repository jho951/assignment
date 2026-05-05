package io.github.jho951.assignment.job.worker

import kotlin.jvm.JvmRecord

@JvmRecord
data class WorkerStartResult(
    val workerJobId: String,
    val status: WorkerRemoteStatus
)

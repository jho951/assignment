package io.github.jho951.assignment.config

import kotlin.jvm.JvmRecord
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "jobs")
@JvmRecord
data class JobProperties(
    val maxAttempts: Int,
    val pollIntervalMs: Long,
    val batchSize: Int,
    val leaseTimeoutMs: Long,
    val cleanupIntervalMs: Long,
    val schedulingEnabled: Boolean,
    val executor: Executor
) {
    @JvmRecord
    data class Executor(
        val coreSize: Int,
        val maxSize: Int,
        val queueCapacity: Int
    )
}

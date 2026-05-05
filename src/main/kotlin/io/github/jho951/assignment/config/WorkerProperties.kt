package io.github.jho951.assignment.config

import kotlin.jvm.JvmRecord
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "worker")
@JvmRecord
data class WorkerProperties(
    val baseUrl: String,
    val issueKeyPath: String,
    val processPath: String,
    val timeoutMs: Long,
    val candidateName: String,
    val candidateEmail: String
)

package io.github.jho951.assignment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jobs")
public record JobProperties(
    int maxAttempts,
    long pollIntervalMs,
    int batchSize,
    long leaseTimeoutMs,
    long cleanupIntervalMs,
    boolean schedulingEnabled,
    ExecutorProperties executor
) {
    public record ExecutorProperties(
        int coreSize,
        int maxSize,
        int queueCapacity
    ) {
    }
}

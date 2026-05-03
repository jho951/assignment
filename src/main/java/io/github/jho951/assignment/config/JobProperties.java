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
        Executor executor
) {

    public record Executor(
            int coreSize,
            int maxSize,
            int queueCapacity
    ) {}
}

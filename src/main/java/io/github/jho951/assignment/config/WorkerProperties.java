package io.github.jho951.assignment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "worker")
public record WorkerProperties(
        String baseUrl,
        String issueKeyPath,
        String processPath,
        long timeoutMs,
        String candidateName,
        String candidateEmail
) {}

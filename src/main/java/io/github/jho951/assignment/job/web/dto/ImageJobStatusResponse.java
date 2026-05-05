package io.github.jho951.assignment.job.web.dto;

import java.time.Instant;

public record ImageJobStatusResponse(
        String jobId,
        String status,
        String imageUrl,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Instant expiresAt,
        ImageJobErrorResponse error
) {}

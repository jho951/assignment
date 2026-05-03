package io.github.jho951.assignment.job.web.dto;

import java.time.Instant;

public record ImageJobResultResponse(
        String jobId,
        String status,
        String result,
        Instant completedAt,
        Instant expiresAt,
        ImageJobErrorResponse error
) {
}

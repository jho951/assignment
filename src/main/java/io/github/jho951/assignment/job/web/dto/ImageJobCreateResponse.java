package io.github.jho951.assignment.job.web.dto;

import java.time.Instant;

public record ImageJobCreateResponse(
        String jobId,
        String status,
        Instant createdAt
) {
}

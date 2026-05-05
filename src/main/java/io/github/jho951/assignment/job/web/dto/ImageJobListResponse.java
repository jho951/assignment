package io.github.jho951.assignment.job.web.dto;

import java.util.List;

public record ImageJobListResponse(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<ImageJobSummaryResponse> items
) {}

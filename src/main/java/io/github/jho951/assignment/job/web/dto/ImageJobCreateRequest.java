package io.github.jho951.assignment.job.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ImageJobCreateRequest(
        @NotBlank(message = "imageUrl is required")
        @Pattern(regexp = "^https?://.+$", message = "imageUrl must be a valid http/https URL")
        String imageUrl
) {}

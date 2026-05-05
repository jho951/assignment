package io.github.jho951.assignment.job.web.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

public record ImageJobCreateResponse(
        @Schema(description = "서버가 발급한 작업 식별자", example = "job_01HZY6J2K6K6M3VY7R4G2T0K9A")
        String jobId,
        @Schema(description = "작업의 현재 상태", example = "QUEUED")
        String status,
        @Schema(description = "작업 생성 시각", example = "2026-05-02T06:00:00Z")
        Instant createdAt
) {}

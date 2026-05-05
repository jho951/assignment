package io.github.jho951.assignment.job.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.jho951.assignment.job.domain.JobStatus;
import io.github.jho951.assignment.job.service.ImageJobCommandService;
import io.github.jho951.assignment.job.service.ImageJobQueryService;
import io.github.jho951.assignment.job.web.dto.ImageJobCreateRequest;
import io.github.jho951.assignment.job.web.dto.ImageJobCreateResponse;
import io.github.jho951.assignment.job.web.dto.ImageJobListResponse;
import io.github.jho951.assignment.job.web.dto.ImageJobResultResponse;
import io.github.jho951.assignment.job.web.dto.ImageJobStatusResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/image-jobs")
@RequiredArgsConstructor
public class ImageJobController {

    private final ImageJobQueryService imageJobQueryService;
    private final ImageJobCommandService imageJobCommandService;

    @PostMapping
    public ResponseEntity<ImageJobCreateResponse> createJob(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ImageJobCreateRequest request
    ) {
        ImageJobCommandService.CreateJobResult result = imageJobCommandService.createJob(idempotencyKey, request);
        ImageJobCreateResponse response = new ImageJobCreateResponse(
                result.imageJob().getJobId(),
                result.imageJob().getStatus().name(),
                result.imageJob().getCreatedAt()
        );
        return result.replayed()
                ? ResponseEntity.ok(response)
                : ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{jobId}")
    public ImageJobStatusResponse getJobStatus(@PathVariable String jobId) {
        return imageJobQueryService.getJobStatus(jobId);
    }

    @GetMapping("/{jobId}/result")
    public ImageJobResultResponse getJobResult(@PathVariable String jobId) {
        return imageJobQueryService.getJobResult(jobId);
    }

    @GetMapping
    public ImageJobListResponse listJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) JobStatus status
    ) {
        return imageJobQueryService.listJobs(page, size, status);
    }
}

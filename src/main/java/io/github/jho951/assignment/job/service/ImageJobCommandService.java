package io.github.jho951.assignment.job.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jho951.assignment.job.domain.ImageJob;
import io.github.jho951.assignment.job.domain.JobFailureCode;
import io.github.jho951.assignment.job.repository.ImageJobRepository;
import io.github.jho951.assignment.job.web.ApiException;
import io.github.jho951.assignment.job.web.dto.ImageJobCreateRequest;

@Service
public class ImageJobCommandService {

    private final ImageJobRepository imageJobRepository;
    private final RequestHashService requestHashService;
    private final Clock clock;

    public ImageJobCommandService(
            ImageJobRepository imageJobRepository,
            RequestHashService requestHashService,
            Clock clock
    ) {
        this.imageJobRepository = imageJobRepository;
        this.requestHashService = requestHashService;
        this.clock = clock;
    }

    @Transactional
    public CreateJobResult createJob(String idempotencyKey, ImageJobCreateRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    JobFailureCode.MISSING_IDEMPOTENCY_KEY,
                    "Idempotency-Key header is required"
            );
        }

        String normalizedKey = idempotencyKey.trim();
        String requestHash = requestHashService.hashImageUrl(request.imageUrl());

        Optional<ImageJob> existingJob = imageJobRepository.findByIdempotencyKey(normalizedKey);
        if (existingJob.isPresent()) {
            ImageJob imageJob = existingJob.get();
            if (!Objects.equals(imageJob.getRequestHash(), requestHash)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        JobFailureCode.IDEMPOTENCY_KEY_CONFLICT,
                        "The same Idempotency-Key was used with a different request body"
                );
            }
            return new CreateJobResult(imageJob, true);
        }

        Instant now = Instant.now(clock);
        ImageJob newJob = ImageJob.queued(
                generateJobId(),
                normalizedKey,
                requestHash,
                request.imageUrl(),
                now
        );

        try {
            return new CreateJobResult(imageJobRepository.save(newJob), false);
        }
        catch (DataIntegrityViolationException exception) {
            ImageJob imageJob = imageJobRepository.findByIdempotencyKey(normalizedKey)
                    .orElseThrow(() -> exception);
            if (!Objects.equals(imageJob.getRequestHash(), requestHash)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        JobFailureCode.IDEMPOTENCY_KEY_CONFLICT,
                        "The same Idempotency-Key was used with a different request body"
                );
            }
            return new CreateJobResult(imageJob, true);
        }
    }

    private String generateJobId() {
        return "job_" + UUID.randomUUID().toString().replace("-", "");
    }

    public record CreateJobResult(
            ImageJob imageJob,
            boolean replayed
    ) {
    }
}

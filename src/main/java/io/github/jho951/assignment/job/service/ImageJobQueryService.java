package io.github.jho951.assignment.job.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jho951.assignment.job.domain.ImageJob;
import io.github.jho951.assignment.job.domain.JobFailureCode;
import io.github.jho951.assignment.job.domain.JobStatus;
import io.github.jho951.assignment.job.repository.ImageJobRepository;
import io.github.jho951.assignment.job.web.ApiException;
import io.github.jho951.assignment.job.web.dto.ImageJobErrorResponse;
import io.github.jho951.assignment.job.web.dto.ImageJobListResponse;
import io.github.jho951.assignment.job.web.dto.ImageJobResultResponse;
import io.github.jho951.assignment.job.web.dto.ImageJobStatusResponse;
import io.github.jho951.assignment.job.web.dto.ImageJobSummaryResponse;

@Service
@Transactional(readOnly = true)
public class ImageJobQueryService {

    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("jobId")
    );

    private final ImageJobRepository imageJobRepository;

    public ImageJobQueryService(ImageJobRepository imageJobRepository) {
        this.imageJobRepository = imageJobRepository;
    }

    public ImageJobStatusResponse getJobStatus(String jobId) {
        ImageJob imageJob = findJob(jobId);
        return new ImageJobStatusResponse(
                imageJob.getJobId(),
                imageJob.getStatus().name(),
                imageJob.getImageUrl(),
                imageJob.getAttemptCount(),
                imageJob.getCreatedAt(),
                imageJob.getUpdatedAt(),
                imageJob.getCompletedAt(),
                imageJob.getExpiresAt(),
                mapError(imageJob)
        );
    }

    public ImageJobResultResponse getJobResult(String jobId) {
        ImageJob imageJob = findJob(jobId);
        if (!imageJob.getStatus().isTerminal()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    JobFailureCode.RESULT_NOT_READY,
                    "The result is not ready yet"
            );
        }
        return new ImageJobResultResponse(
                imageJob.getJobId(),
                imageJob.getStatus().name(),
                imageJob.getResult(),
                imageJob.getCompletedAt(),
                imageJob.getExpiresAt(),
                mapError(imageJob)
        );
    }

    public ImageJobListResponse listJobs(int page, int size, JobStatus status) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(normalizedPage, normalizedSize, DEFAULT_SORT);

        Page<ImageJob> imageJobs = status == null
                ? imageJobRepository.findAll(pageable)
                : imageJobRepository.findAllByStatus(status, pageable);

        List<ImageJobSummaryResponse> items = imageJobs.getContent().stream()
                .map(job -> new ImageJobSummaryResponse(
                        job.getJobId(),
                        job.getStatus().name(),
                        job.getImageUrl(),
                        job.getAttemptCount(),
                        job.getCreatedAt(),
                        job.getUpdatedAt(),
                        job.getCompletedAt(),
                        job.getExpiresAt(),
                        mapError(job)
                ))
                .toList();

        return new ImageJobListResponse(
                imageJobs.getNumber(),
                imageJobs.getSize(),
                imageJobs.getTotalElements(),
                imageJobs.getTotalPages(),
                items
        );
    }

    private ImageJob findJob(String jobId) {
        return imageJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        JobFailureCode.JOB_NOT_FOUND,
                        "Image job not found"
                ));
    }

    private ImageJobErrorResponse mapError(ImageJob imageJob) {
        if (imageJob.getErrorCode() == null) {
            return null;
        }
        return new ImageJobErrorResponse(imageJob.getErrorCode(), imageJob.getErrorMessage());
    }
}

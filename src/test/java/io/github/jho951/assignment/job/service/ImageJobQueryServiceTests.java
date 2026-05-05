package io.github.jho951.assignment.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import io.github.jho951.assignment.job.domain.ImageJob;
import io.github.jho951.assignment.job.domain.JobFailureCode;
import io.github.jho951.assignment.job.domain.JobStatus;
import io.github.jho951.assignment.job.repository.ImageJobRepository;
import io.github.jho951.assignment.job.web.ApiException;
import io.github.jho951.assignment.job.web.dto.ImageJobListResponse;
import io.github.jho951.assignment.job.web.dto.ImageJobResultResponse;
import io.github.jho951.assignment.job.web.dto.ImageJobStatusResponse;

@ExtendWith(MockitoExtension.class)
class ImageJobQueryServiceTests {

    @Mock
    private ImageJobRepository imageJobRepository;

    private ImageJobQueryService imageJobQueryService;

    @BeforeEach
    void setUp() {
        imageJobQueryService = new ImageJobQueryService(imageJobRepository);
    }

    @Test
    void shouldReturnJobStatusWithMappedError() {
        ImageJob imageJob = job("job-1", JobStatus.FAILED);
        imageJob.setAttemptCount(2);
        imageJob.setErrorCode(JobFailureCode.WORKER_TIMEOUT.name());
        imageJob.setErrorMessage("timed out");
        when(imageJobRepository.findByJobId("job-1")).thenReturn(Optional.of(imageJob));

        ImageJobStatusResponse response = imageJobQueryService.getJobStatus("job-1");

        assertThat(response.jobId()).isEqualTo("job-1");
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.imageUrl()).isEqualTo("https://example.com/job-1.png");
        assertThat(response.attemptCount()).isEqualTo(2);
        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(JobFailureCode.WORKER_TIMEOUT.name());
        assertThat(response.error().message()).isEqualTo("timed out");
    }

    @Test
    void shouldThrowNotFoundWhenJobStatusIsMissing() {
        when(imageJobRepository.findByJobId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> imageJobQueryService.getJobStatus("missing"))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertApiException(
                        (ApiException) exception,
                        HttpStatus.NOT_FOUND,
                        JobFailureCode.JOB_NOT_FOUND
                ));
    }

    @Test
    void shouldThrowConflictWhenResultIsNotReady() {
        ImageJob imageJob = job("job-2", JobStatus.PROCESSING);
        when(imageJobRepository.findByJobId("job-2")).thenReturn(Optional.of(imageJob));

        assertThatThrownBy(() -> imageJobQueryService.getJobResult("job-2"))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertApiException(
                        (ApiException) exception,
                        HttpStatus.CONFLICT,
                        JobFailureCode.RESULT_NOT_READY
                ));
    }

    @Test
    void shouldReturnTerminalJobResultWithMappedError() {
        ImageJob imageJob = job("job-3", JobStatus.FAILED);
        imageJob.setResult(null);
        imageJob.setCompletedAt(Instant.parse("2026-05-05T00:01:00Z"));
        imageJob.setExpiresAt(Instant.parse("2026-05-12T00:01:00Z"));
        imageJob.setErrorCode(JobFailureCode.WORKER_UNAVAILABLE.name());
        imageJob.setErrorMessage("worker unavailable");
        when(imageJobRepository.findByJobId("job-3")).thenReturn(Optional.of(imageJob));

        ImageJobResultResponse response = imageJobQueryService.getJobResult("job-3");

        assertThat(response.jobId()).isEqualTo("job-3");
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(JobFailureCode.WORKER_UNAVAILABLE.name());
        assertThat(response.completedAt()).isEqualTo(Instant.parse("2026-05-05T00:01:00Z"));
        assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-05-12T00:01:00Z"));
    }

    @Test
    void shouldListJobsUsingNormalizedPaginationAndDefaultSort() {
        ImageJob imageJob = job("job-4", JobStatus.QUEUED);
        Page<ImageJob> page = new PageImpl<>(
                List.of(imageJob),
                PageRequest.of(0, 100, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("jobId"))),
                1
        );
        when(imageJobRepository.findAll(any(Pageable.class))).thenReturn(page);

        ImageJobListResponse response = imageJobQueryService.listJobs(-3, 500, null);

        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(100);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).jobId()).isEqualTo("job-4");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(imageJobRepository).findAll(captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(100);
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getSort().getOrderFor("jobId").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void shouldListJobsByStatusFilter() {
        ImageJob imageJob = job("job-5", JobStatus.FAILED);
        imageJob.setErrorCode(JobFailureCode.INTERNAL_ERROR.name());
        imageJob.setErrorMessage("boom");
        Page<ImageJob> page = new PageImpl<>(List.of(imageJob), PageRequest.of(1, 5), 6);
        when(imageJobRepository.findAllByStatus(eq(JobStatus.FAILED), any(Pageable.class))).thenReturn(page);

        ImageJobListResponse response = imageJobQueryService.listJobs(1, 5, JobStatus.FAILED);

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(5);
        assertThat(response.totalElements()).isEqualTo(6);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).error()).isNotNull();
        assertThat(response.items().get(0).error().code()).isEqualTo(JobFailureCode.INTERNAL_ERROR.name());
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(imageJobRepository).findAllByStatus(eq(JobStatus.FAILED), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getSort().getOrderFor("jobId").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    private void assertApiException(ApiException exception, HttpStatus httpStatus, JobFailureCode code) {
        assertThat(exception.getHttpStatus()).isEqualTo(httpStatus);
        assertThat(exception.getCode()).isEqualTo(code);
    }

    private ImageJob job(String jobId, JobStatus status) {
        ImageJob imageJob = ImageJob.queued(
                jobId,
                "idem-" + jobId,
                "hash-" + jobId,
                "https://example.com/" + jobId + ".png",
                Instant.parse("2026-05-05T00:00:00Z")
        );
        imageJob.setStatus(status);
        return imageJob;
    }
}

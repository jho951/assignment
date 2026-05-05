package io.github.jho951.assignment.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import io.github.jho951.assignment.job.domain.ImageJob;
import io.github.jho951.assignment.job.domain.JobFailureCode;
import io.github.jho951.assignment.job.domain.JobStatus;
import io.github.jho951.assignment.job.repository.ImageJobRepository;
import io.github.jho951.assignment.job.web.ApiException;
import io.github.jho951.assignment.job.web.dto.ImageJobCreateRequest;

@ExtendWith(MockitoExtension.class)
class ImageJobCommandServiceTests {

    private static final Instant NOW = Instant.parse("2026-05-05T00:00:00Z");

    @Mock
    private ImageJobRepository imageJobRepository;

    @Mock
    private RequestHashService requestHashService;

    private ImageJobCommandService imageJobCommandService;

    @BeforeEach
    void setUp() {
        imageJobCommandService = new ImageJobCommandService(
                imageJobRepository,
                requestHashService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldRejectMissingIdempotencyKey() {
        assertThatThrownBy(() -> imageJobCommandService.createJob(null, request("https://example.com/input.png")))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertApiException(
                        (ApiException) exception,
                        HttpStatus.BAD_REQUEST,
                        JobFailureCode.MISSING_IDEMPOTENCY_KEY
                ));

        verifyNoInteractions(requestHashService, imageJobRepository);
    }

    @Test
    void shouldRejectInvalidIdempotencyKeyAfterTrim() {
        assertThatThrownBy(() -> imageJobCommandService.createJob(" invalid key ", request("https://example.com/input.png")))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertApiException(
                        (ApiException) exception,
                        HttpStatus.BAD_REQUEST,
                        JobFailureCode.INVALID_IDEMPOTENCY_KEY
                ));

        verifyNoInteractions(requestHashService);
    }

    @Test
    void shouldReplayExistingJobWhenKeyAndHashMatch() {
        ImageJob existingJob = queuedJob("job-existing", "idem-1", "hash-1", "https://example.com/input.png");
        when(requestHashService.hashImageUrl("https://example.com/input.png")).thenReturn("hash-1");
        when(imageJobRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(existingJob));

        ImageJobCommandService.CreateJobResult result = imageJobCommandService.createJob(
                " idem-1 ",
                request("https://example.com/input.png")
        );

        assertThat(result.replayed()).isTrue();
        assertThat(result.imageJob()).isSameAs(existingJob);
    }

    @Test
    void shouldRejectExistingJobWhenRequestHashDiffers() {
        ImageJob existingJob = queuedJob("job-existing", "idem-2", "hash-1", "https://example.com/input-1.png");
        when(requestHashService.hashImageUrl("https://example.com/input-2.png")).thenReturn("hash-2");
        when(imageJobRepository.findByIdempotencyKey("idem-2")).thenReturn(Optional.of(existingJob));

        assertThatThrownBy(() -> imageJobCommandService.createJob("idem-2", request("https://example.com/input-2.png")))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertApiException(
                        (ApiException) exception,
                        HttpStatus.CONFLICT,
                        JobFailureCode.IDEMPOTENCY_KEY_CONFLICT
                ));
    }

    @Test
    void shouldCreateNewQueuedJobWithNormalizedKey() {
        when(requestHashService.hashImageUrl("https://example.com/input.png")).thenReturn("hash-new");
        when(imageJobRepository.findByIdempotencyKey("idem-3")).thenReturn(Optional.empty());
        when(imageJobRepository.save(any(ImageJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ImageJobCommandService.CreateJobResult result = imageJobCommandService.createJob(
                " idem-3 ",
                request("https://example.com/input.png")
        );

        assertThat(result.replayed()).isFalse();
        assertThat(result.imageJob().getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(result.imageJob().getIdempotencyKey()).isEqualTo("idem-3");
        assertThat(result.imageJob().getRequestHash()).isEqualTo("hash-new");
        assertThat(result.imageJob().getImageUrl()).isEqualTo("https://example.com/input.png");
        assertThat(result.imageJob().getNextAttemptAt()).isEqualTo(NOW);
        assertThat(result.imageJob().getJobId()).startsWith("job_");

        ArgumentCaptor<ImageJob> captor = ArgumentCaptor.forClass(ImageJob.class);
        verify(imageJobRepository).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("idem-3");
    }

    @Test
    void shouldReplayExistingJobWhenConcurrentInsertCollidesWithSameHash() {
        ImageJob existingJob = queuedJob("job-existing", "idem-4", "hash-4", "https://example.com/input.png");
        when(requestHashService.hashImageUrl("https://example.com/input.png")).thenReturn("hash-4");
        when(imageJobRepository.findByIdempotencyKey("idem-4"))
                .thenReturn(Optional.empty(), Optional.of(existingJob));
        when(imageJobRepository.save(any(ImageJob.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate idempotency key"));

        ImageJobCommandService.CreateJobResult result = imageJobCommandService.createJob(
                "idem-4",
                request("https://example.com/input.png")
        );

        assertThat(result.replayed()).isTrue();
        assertThat(result.imageJob()).isSameAs(existingJob);
    }

    @Test
    void shouldRejectConcurrentInsertCollisionWhenRequestHashDiffers() {
        ImageJob existingJob = queuedJob("job-existing", "idem-5", "hash-existing", "https://example.com/input-1.png");
        when(requestHashService.hashImageUrl("https://example.com/input-2.png")).thenReturn("hash-new");
        when(imageJobRepository.findByIdempotencyKey("idem-5"))
                .thenReturn(Optional.empty(), Optional.of(existingJob));
        when(imageJobRepository.save(any(ImageJob.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate idempotency key"));

        assertThatThrownBy(() -> imageJobCommandService.createJob("idem-5", request("https://example.com/input-2.png")))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertApiException(
                        (ApiException) exception,
                        HttpStatus.CONFLICT,
                        JobFailureCode.IDEMPOTENCY_KEY_CONFLICT
                ));
    }

    private void assertApiException(ApiException exception, HttpStatus httpStatus, JobFailureCode code) {
        assertThat(exception.getHttpStatus()).isEqualTo(httpStatus);
        assertThat(exception.getCode()).isEqualTo(code);
    }

    private ImageJobCreateRequest request(String imageUrl) {
        return new ImageJobCreateRequest(imageUrl);
    }

    private ImageJob queuedJob(String jobId, String key, String requestHash, String imageUrl) {
        return ImageJob.queued(jobId, key, requestHash, imageUrl, NOW);
    }
}

package io.github.jho951.assignment.job.service

import io.github.jho951.assignment.job.domain.ImageJob
import io.github.jho951.assignment.job.domain.JobFailureCode
import io.github.jho951.assignment.job.domain.JobStatus
import io.github.jho951.assignment.job.repository.ImageJobRepository
import io.github.jho951.assignment.job.web.ApiException
import io.github.jho951.assignment.job.web.dto.ImageJobCreateRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus

@ExtendWith(MockitoExtension::class)
class ImageJobCommandServiceTests {

    @Mock
    private lateinit var imageJobRepository: ImageJobRepository

    @Mock
    private lateinit var requestHashService: RequestHashService

    private lateinit var imageJobCommandService: ImageJobCommandService

    @BeforeEach
    fun setUp() {
        imageJobCommandService = ImageJobCommandService(
            imageJobRepository,
            requestHashService,
            Clock.fixed(NOW, ZoneOffset.UTC)
        )
    }

    @Test
    fun shouldRejectMissingIdempotencyKey() {
        assertThatThrownBy { imageJobCommandService.createJob(null, request("https://example.com/input.png")) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertApiException(
                    it,
                    HttpStatus.BAD_REQUEST,
                    JobFailureCode.MISSING_IDEMPOTENCY_KEY
                )
            }

        verifyNoInteractions(requestHashService, imageJobRepository)
    }

    @Test
    fun shouldRejectInvalidIdempotencyKeyAfterTrim() {
        assertThatThrownBy { imageJobCommandService.createJob(" invalid key ", request("https://example.com/input.png")) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertApiException(
                    it,
                    HttpStatus.BAD_REQUEST,
                    JobFailureCode.INVALID_IDEMPOTENCY_KEY
                )
            }

        verifyNoInteractions(requestHashService)
    }

    @Test
    fun shouldReplayExistingJobWhenKeyAndHashMatch() {
        val existingJob = queuedJob("job-existing", "idem-1", "hash-1", "https://example.com/input.png")
        whenever(requestHashService.hashImageUrl("https://example.com/input.png")).thenReturn("hash-1")
        whenever(imageJobRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(existingJob))

        val result = imageJobCommandService.createJob(
            " idem-1 ",
            request("https://example.com/input.png")
        )

        assertThat(result.replayed).isTrue()
        assertThat(result.imageJob).isSameAs(existingJob)
    }

    @Test
    fun shouldRejectExistingJobWhenRequestHashDiffers() {
        val existingJob = queuedJob("job-existing", "idem-2", "hash-1", "https://example.com/input-1.png")
        whenever(requestHashService.hashImageUrl("https://example.com/input-2.png")).thenReturn("hash-2")
        whenever(imageJobRepository.findByIdempotencyKey("idem-2")).thenReturn(Optional.of(existingJob))

        assertThatThrownBy { imageJobCommandService.createJob("idem-2", request("https://example.com/input-2.png")) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertApiException(
                    it,
                    HttpStatus.CONFLICT,
                    JobFailureCode.IDEMPOTENCY_KEY_CONFLICT
                )
            }
    }

    @Test
    fun shouldCreateNewQueuedJobWithNormalizedKey() {
        whenever(requestHashService.hashImageUrl("https://example.com/input.png")).thenReturn("hash-new")
        whenever(imageJobRepository.findByIdempotencyKey("idem-3")).thenReturn(Optional.empty())
        whenever(imageJobRepository.save(any(ImageJob::class.java))).thenAnswer { it.getArgument(0) }

        val result = imageJobCommandService.createJob(
            " idem-3 ",
            request("https://example.com/input.png")
        )

        assertThat(result.replayed).isFalse()
        assertThat(result.imageJob.status).isEqualTo(JobStatus.QUEUED)
        assertThat(result.imageJob.idempotencyKey).isEqualTo("idem-3")
        assertThat(result.imageJob.requestHash).isEqualTo("hash-new")
        assertThat(result.imageJob.imageUrl).isEqualTo("https://example.com/input.png")
        assertThat(result.imageJob.nextAttemptAt).isEqualTo(NOW)
        assertThat(result.imageJob.jobId).startsWith("job_")

        val captor = ArgumentCaptor.forClass(ImageJob::class.java)
        verify(imageJobRepository).save(captor.capture())
        assertThat(captor.value.idempotencyKey).isEqualTo("idem-3")
    }

    @Test
    fun shouldReplayExistingJobWhenConcurrentInsertCollidesWithSameHash() {
        val existingJob = queuedJob("job-existing", "idem-4", "hash-4", "https://example.com/input.png")
        whenever(requestHashService.hashImageUrl("https://example.com/input.png")).thenReturn("hash-4")
        whenever(imageJobRepository.findByIdempotencyKey("idem-4"))
            .thenReturn(Optional.empty(), Optional.of(existingJob))
        whenever(imageJobRepository.save(any(ImageJob::class.java)))
            .thenThrow(DataIntegrityViolationException("duplicate idempotency key"))

        val result = imageJobCommandService.createJob(
            "idem-4",
            request("https://example.com/input.png")
        )

        assertThat(result.replayed).isTrue()
        assertThat(result.imageJob).isSameAs(existingJob)
    }

    @Test
    fun shouldRejectConcurrentInsertCollisionWhenRequestHashDiffers() {
        val existingJob = queuedJob("job-existing", "idem-5", "hash-existing", "https://example.com/input-1.png")
        whenever(requestHashService.hashImageUrl("https://example.com/input-2.png")).thenReturn("hash-new")
        whenever(imageJobRepository.findByIdempotencyKey("idem-5"))
            .thenReturn(Optional.empty(), Optional.of(existingJob))
        whenever(imageJobRepository.save(any(ImageJob::class.java)))
            .thenThrow(DataIntegrityViolationException("duplicate idempotency key"))

        assertThatThrownBy { imageJobCommandService.createJob("idem-5", request("https://example.com/input-2.png")) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertApiException(
                    it,
                    HttpStatus.CONFLICT,
                    JobFailureCode.IDEMPOTENCY_KEY_CONFLICT
                )
            }
    }

    private fun assertApiException(exception: ApiException, httpStatus: HttpStatus, code: JobFailureCode) {
        assertThat(exception.httpStatus).isEqualTo(httpStatus)
        assertThat(exception.code).isEqualTo(code)
    }

    private fun request(imageUrl: String): ImageJobCreateRequest = ImageJobCreateRequest(imageUrl)

    private fun queuedJob(jobId: String, key: String, requestHash: String, imageUrl: String): ImageJob =
        ImageJob.queued(jobId, key, requestHash, imageUrl, NOW)

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-05T00:00:00Z")
    }
}

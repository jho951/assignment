package io.github.jho951.assignment.job.web

import io.github.jho951.assignment.job.domain.JobStatus
import io.github.jho951.assignment.job.repository.ImageJobRepository
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
    properties = [
        "jobs.scheduling-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:assignment-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password="
    ]
)
class ImageJobControllerIntegrationTests {

    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var imageJobRepository: ImageJobRepository

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        imageJobRepository.deleteAll()
    }

    @Test
    fun shouldCreateJobAndReturnAccepted() {
        mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "idem-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "imageUrl": "https://example.com/images/input.png"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("\$.jobId").isNotEmpty)
            .andExpect(jsonPath("\$.status").value("QUEUED"))
    }

    @Test
    fun shouldReplayExistingJobForSameIdempotencyKeyAndPayload() {
        val responseContent = mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "idem-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input.png"}""")
        )
            .andExpect(status().isAccepted)
            .andReturn()
            .response
            .contentAsString

        val createdJob: JsonNode = objectMapper.readTree(responseContent)

        mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "idem-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input.png"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.jobId").value(createdJob.get("jobId").textValue()))
            .andExpect(jsonPath("\$.status").value("QUEUED"))
    }

    @Test
    fun shouldReturnCurrentProcessingStatusForReplayOfSameIdempotencyKeyAndPayload() {
        val responseContent = mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "idem-2-processing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input.png"}""")
        )
            .andExpect(status().isAccepted)
            .andReturn()
            .response
            .contentAsString

        val createdJobId = objectMapper.readTree(responseContent).get("jobId").textValue()
        val storedJob = imageJobRepository.findByJobId(createdJobId).orElseThrow()
        storedJob.status = JobStatus.PROCESSING
        imageJobRepository.save(storedJob)

        mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "idem-2-processing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input.png"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.jobId").value(createdJobId))
            .andExpect(jsonPath("\$.status").value("PROCESSING"))

        assertThat(imageJobRepository.findAll()).hasSize(1)
    }

    @Test
    fun shouldCreateNewJobForSameImageUrlWithDifferentIdempotencyKeys() {
        val firstResponse = mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "idem-2a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input.png"}""")
        )
            .andExpect(status().isAccepted)
            .andReturn()
            .response
            .contentAsString

        val secondResponse = mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "idem-2b")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input.png"}""")
        )
            .andExpect(status().isAccepted)
            .andReturn()
            .response
            .contentAsString

        val firstJobId = objectMapper.readTree(firstResponse).get("jobId").textValue()
        val secondJobId = objectMapper.readTree(secondResponse).get("jobId").textValue()

        assertThat(firstJobId).isNotEqualTo(secondJobId)
    }

    @Test
    fun shouldRejectDifferentPayloadForSameIdempotencyKey() {
        mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "idem-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input-1.png"}""")
        )
            .andExpect(status().isAccepted)

        mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "idem-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input-2.png"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("\$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
    }

    @Test
    fun shouldRejectBlankIdempotencyKey() {
        mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "   ")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input.png"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("\$.code").value("INVALID_IDEMPOTENCY_KEY"))
    }

    @Test
    fun shouldRejectMalformedIdempotencyKey() {
        mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "idem key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input.png"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("\$.code").value("INVALID_IDEMPOTENCY_KEY"))
    }

    @Test
    fun shouldRejectTooLongIdempotencyKey() {
        mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "a".repeat(129))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input.png"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("\$.code").value("INVALID_IDEMPOTENCY_KEY"))
    }

    @Test
    fun shouldRejectMissingIdempotencyKey() {
        mockMvc.perform(
            post("/api/v1/image-jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input.png"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("\$.code").value("MISSING_IDEMPOTENCY_KEY"))
    }

    @Test
    fun shouldReturnQueuedJobStatusAndConflictForResultBeforeCompletion() {
        val responseContent = mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "idem-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input.png"}""")
        )
            .andExpect(status().isAccepted)
            .andReturn()
            .response
            .contentAsString

        val jobId = objectMapper.readTree(responseContent).get("jobId").textValue()

        mockMvc.perform(get("/api/v1/image-jobs/{jobId}", jobId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.jobId").value(jobId))
            .andExpect(jsonPath("\$.status").value("QUEUED"))

        mockMvc.perform(get("/api/v1/image-jobs/{jobId}/result", jobId))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("\$.code").value("RESULT_NOT_READY"))
    }

    @Test
    fun shouldHideExpiredTerminalJobBeforeCleanupRuns() {
        val responseContent = mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "idem-expired")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input.png"}""")
        )
            .andExpect(status().isAccepted)
            .andReturn()
            .response
            .contentAsString

        val jobId = objectMapper.readTree(responseContent).get("jobId").textValue()
        val storedJob = imageJobRepository.findByJobId(jobId).orElseThrow()
        val now = Instant.now()
        storedJob.status = JobStatus.SUCCEEDED
        storedJob.result = "https://example.com/results/output.png"
        storedJob.completedAt = now.minus(Duration.ofDays(8))
        storedJob.expiresAt = now.minusSeconds(1)
        imageJobRepository.save(storedJob)

        mockMvc.perform(get("/api/v1/image-jobs/{jobId}", jobId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("\$.code").value("JOB_NOT_FOUND"))

        mockMvc.perform(get("/api/v1/image-jobs/{jobId}/result", jobId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("\$.code").value("JOB_NOT_FOUND"))

        mockMvc.perform(get("/api/v1/image-jobs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.totalElements").value(0))
            .andExpect(jsonPath("\$.items", hasSize<Any>(0)))

        mockMvc.perform(get("/api/v1/image-jobs").param("status", "SUCCEEDED"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.totalElements").value(0))
            .andExpect(jsonPath("\$.items", hasSize<Any>(0)))
    }

    @Test
    fun shouldListJobsUsingDefaultPagination() {
        mockMvc.perform(
            post("/api/v1/image-jobs")
                .header("Idempotency-Key", "idem-5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrl":"https://example.com/images/input.png"}""")
        )
            .andExpect(status().isAccepted)

        mockMvc.perform(get("/api/v1/image-jobs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.page").value(0))
            .andExpect(jsonPath("\$.size").value(20))
            .andExpect(jsonPath("\$.totalElements").value(1))
            .andExpect(jsonPath("\$.items", hasSize<Any>(1)))
    }

    @Test
    fun shouldExposeOpenApiDocumentForPublicApi() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.openapi").value("3.1.0"))
            .andExpect(jsonPath("\$.info.title").value("Assignment Image Job API"))
            .andExpect(jsonPath("\$.paths['/api/v1/image-jobs']").exists())
    }

    @Test
    fun shouldExposeSwaggerUiEntryPointForPublicApi() {
        mockMvc.perform(get("/swagger-ui.html"))
            .andExpect(status().is3xxRedirection)
    }
}

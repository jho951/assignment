package io.github.jho951.assignment.job.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.github.jho951.assignment.job.domain.ImageJob;
import io.github.jho951.assignment.job.domain.JobStatus;
import io.github.jho951.assignment.job.repository.ImageJobRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "jobs.scheduling-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:assignment-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class ImageJobControllerIntegrationTests {

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ImageJobRepository imageJobRepository;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        imageJobRepository.deleteAll();
    }

    @Test
    void shouldCreateJobAndReturnAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/image-jobs")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input.png"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void shouldReplayExistingJobForSameIdempotencyKeyAndPayload() throws Exception {
        String responseContent = mockMvc.perform(post("/api/v1/image-jobs")
                        .header("Idempotency-Key", "idem-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input.png"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode createdJob = objectMapper.readTree(responseContent);

        mockMvc.perform(post("/api/v1/image-jobs")
                        .header("Idempotency-Key", "idem-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input.png"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(createdJob.get("jobId").asText()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void shouldReturnCurrentProcessingStatusForReplayOfSameIdempotencyKeyAndPayload() throws Exception {
        String responseContent = mockMvc.perform(post("/api/v1/image-jobs")
                        .header("Idempotency-Key", "idem-2-processing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input.png"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String createdJobId = objectMapper.readTree(responseContent).get("jobId").asText();
        ImageJob storedJob = imageJobRepository.findByJobId(createdJobId).orElseThrow();
        storedJob.setStatus(JobStatus.PROCESSING);
        imageJobRepository.save(storedJob);

        mockMvc.perform(post("/api/v1/image-jobs")
                        .header("Idempotency-Key", "idem-2-processing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input.png"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(createdJobId))
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        org.assertj.core.api.Assertions.assertThat(imageJobRepository.findAll()).hasSize(1);
    }

    @Test
    void shouldCreateNewJobForSameImageUrlWithDifferentIdempotencyKeys() throws Exception {
        String firstResponse = mockMvc.perform(post("/api/v1/image-jobs")
                        .header("Idempotency-Key", "idem-2a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input.png"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String secondResponse = mockMvc.perform(post("/api/v1/image-jobs")
                        .header("Idempotency-Key", "idem-2b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input.png"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String firstJobId = objectMapper.readTree(firstResponse).get("jobId").asText();
        String secondJobId = objectMapper.readTree(secondResponse).get("jobId").asText();

        org.assertj.core.api.Assertions.assertThat(firstJobId).isNotEqualTo(secondJobId);
    }

    @Test
    void shouldRejectDifferentPayloadForSameIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/image-jobs")
                        .header("Idempotency-Key", "idem-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input-1.png"
                                }
                                """))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/image-jobs")
                        .header("Idempotency-Key", "idem-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input-2.png"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void shouldRejectBlankIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/image-jobs")
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input.png"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void shouldRejectMalformedIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/image-jobs")
                        .header("Idempotency-Key", "idem key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input.png"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void shouldRejectTooLongIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/image-jobs")
                        .header("Idempotency-Key", "a".repeat(129))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input.png"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/image-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input.png"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void shouldReturnQueuedJobStatusAndConflictForResultBeforeCompletion() throws Exception {
        String responseContent = mockMvc.perform(post("/api/v1/image-jobs")
                        .header("Idempotency-Key", "idem-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input.png"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String jobId = objectMapper.readTree(responseContent).get("jobId").asText();

        mockMvc.perform(get("/api/v1/image-jobs/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        mockMvc.perform(get("/api/v1/image-jobs/{jobId}/result", jobId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESULT_NOT_READY"));
    }

    @Test
    void shouldListJobsUsingDefaultPagination() throws Exception {
        mockMvc.perform(post("/api/v1/image-jobs")
                        .header("Idempotency-Key", "idem-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageUrl": "https://example.com/images/input.png"
                                }
                                """))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/image-jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void shouldExposeOpenApiDocumentForPublicApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.info.title").value("Assignment Image Job API"))
                .andExpect(jsonPath("$.paths['/api/v1/image-jobs']").exists());
    }

    @Test
    void shouldExposeSwaggerUiEntryPointForPublicApi() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }
}

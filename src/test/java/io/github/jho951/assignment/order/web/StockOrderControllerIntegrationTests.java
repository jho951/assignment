package io.github.jho951.assignment.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jho951.assignment.order.domain.JobStatus;
import io.github.jho951.assignment.order.repository.StockOrderJobRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(
    properties = {
        "jobs.scheduling-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:assignment-web;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password="
    }
)
class StockOrderControllerIntegrationTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @Autowired
    private StockOrderJobRepository repository;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        repository.deleteAll();
    }

    @Test
    void shouldCreateJobAndReturnAccepted() throws Exception {
        mockMvc.perform(
                post("/api/v1/stock-orders")
                    .header("Idempotency-Key", "idem-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody())
            )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").isNotEmpty())
            .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void shouldReplayExistingJob() throws Exception {
        String content = mockMvc.perform(
                post("/api/v1/stock-orders")
                    .header("Idempotency-Key", "idem-2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody())
            )
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode created = objectMapper.readTree(content);

        mockMvc.perform(
                post("/api/v1/stock-orders")
                    .header("Idempotency-Key", "idem-2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody())
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value(created.get("jobId").asText()))
            .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void shouldRejectDifferentPayloadForSameIdempotencyKey() throws Exception {
        mockMvc.perform(
                post("/api/v1/stock-orders")
                    .header("Idempotency-Key", "idem-3")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody())
            )
            .andExpect(status().isAccepted());

        mockMvc.perform(
                post("/api/v1/stock-orders")
                    .header("Idempotency-Key", "idem-3")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "brokerageCode": "KIS",
                          "accountNumber": "12345678-01",
                          "symbol": "000660",
                          "side": "BUY",
                          "orderType": "LIMIT",
                          "quantity": 10,
                          "price": 70000
                        }
                        """)
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void shouldShowStatusAndHideExpiredTerminalJobs() throws Exception {
        String content = mockMvc.perform(
                post("/api/v1/stock-orders")
                    .header("Idempotency-Key", "idem-4")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody())
            )
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String jobId = objectMapper.readTree(content).get("jobId").asText();

        mockMvc.perform(get("/api/v1/stock-orders/{jobId}", jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.symbol").value("005930"));

        var job = repository.findByJobId(jobId).orElseThrow();
        job.setStatus(JobStatus.SUCCEEDED);
        job.setExternalOrderId("br-1");
        job.setFilledQuantity(10);
        job.setRemainingQuantity(0);
        job.setAverageExecutedPrice(new BigDecimal("69950"));
        job.setCompletedAt(Instant.now().minus(Duration.ofDays(8)));
        job.setExpiresAt(Instant.now().minusSeconds(1));
        repository.save(job);

        mockMvc.perform(get("/api/v1/stock-orders/{jobId}", jobId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }

    @Test
    void shouldReturnValidationErrorForMissingPriceOnLimitOrder() throws Exception {
        mockMvc.perform(
                post("/api/v1/stock-orders")
                    .header("Idempotency-Key", "idem-5")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "brokerageCode": "KIS",
                          "accountNumber": "12345678-01",
                          "symbol": "005930",
                          "side": "BUY",
                          "orderType": "LIMIT",
                          "quantity": 10
                        }
                        """)
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private String requestBody() {
        return """
            {
              "brokerageCode": "KIS",
              "accountNumber": "12345678-01",
              "symbol": "005930",
              "side": "BUY",
              "orderType": "LIMIT",
              "quantity": 10,
              "price": 70000
            }
            """;
    }
}

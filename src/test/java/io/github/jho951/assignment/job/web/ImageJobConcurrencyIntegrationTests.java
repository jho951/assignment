package io.github.jho951.assignment.job.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import io.github.jho951.assignment.job.domain.ImageJob;
import io.github.jho951.assignment.job.repository.ImageJobRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jobs.scheduling-enabled=false",
                "spring.datasource.url=jdbc:h2:mem:assignment-concurrency-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        }
)
class ImageJobConcurrencyIntegrationTests {

    private static final String SAME_KEY = "idem-concurrent-1";
    private static final String IMAGE_URL = "https://example.com/images/input.png";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ImageJobRepository imageJobRepository;

    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        imageJobRepository.deleteAll();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    void shouldConvergeConcurrentRequestsWithSameIdempotencyKeyToSingleJob() throws Exception {
        int concurrency = 8;
        ExecutorService executorService = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<CreateJobResponse>> futures = IntStream.range(0, concurrency)
                    .mapToObj(index -> executorService.submit(() -> {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting for concurrent start signal");
                        }
                        return sendCreateJobRequest(SAME_KEY, IMAGE_URL);
                    }))
                    .toList();

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<CreateJobResponse> responses = new ArrayList<>(concurrency);
            for (Future<CreateJobResponse> future : futures) {
                responses.add(future.get(10, TimeUnit.SECONDS));
            }

            executorService.shutdown();
            assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));

            long acceptedCount = responses.stream()
                    .filter(response -> response.httpStatus() == 202)
                    .count();
            long replayCount = responses.stream()
                    .filter(response -> response.httpStatus() == 200)
                    .count();

            List<CreateJobResponse> unexpectedResponses = responses.stream()
                    .filter(response -> response.httpStatus() != 200 && response.httpStatus() != 202)
                    .toList();

            assertEquals(1L, acceptedCount);
            assertEquals(concurrency - 1L, replayCount);
            assertTrue(unexpectedResponses.isEmpty(), unexpectedResponses.toString());
            assertTrue(responses.stream().allMatch(response -> "QUEUED".equals(response.status())));

            Set<String> jobIds = responses.stream()
                    .map(CreateJobResponse::jobId)
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(1, jobIds.size());

            List<ImageJob> storedJobs = imageJobRepository.findAll();
            assertEquals(1, storedJobs.size());
            assertEquals(storedJobs.get(0).getJobId(), jobIds.iterator().next());
        }
        finally {
            executorService.shutdownNow();
        }
    }

    private CreateJobResponse sendCreateJobRequest(String idempotencyKey, String imageUrl) throws Exception {
        String requestBody = """
                {
                  "imageUrl": "%s"
                }
                """.formatted(imageUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/image-jobs"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode jsonNode = objectMapper.readTree(response.body());
        return new CreateJobResponse(
                response.statusCode(),
                jsonNode.path("jobId").asText(null),
                jsonNode.path("status").asText(null),
                response.body()
        );
    }

    private record CreateJobResponse(
            int httpStatus,
            String jobId,
            String status,
            String body
    ) {
    }
}

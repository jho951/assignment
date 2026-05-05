package io.github.jho951.assignment.job.web

import io.github.jho951.assignment.job.domain.ImageJob
import io.github.jho951.assignment.job.repository.ImageJobRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.stream.IntStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "jobs.scheduling-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:assignment-concurrency-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password="
    ]
)
class ImageJobConcurrencyIntegrationTests {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var imageJobRepository: ImageJobRepository

    private lateinit var httpClient: HttpClient

    @BeforeEach
    fun setUp() {
        imageJobRepository.deleteAll()
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
    }

    @Test
    fun shouldConvergeConcurrentRequestsWithSameIdempotencyKeyToSingleJob() {
        val concurrency = 8
        val executorService: ExecutorService = Executors.newFixedThreadPool(concurrency)
        val ready = CountDownLatch(concurrency)
        val start = CountDownLatch(1)

        try {
            val futures: List<Future<CreateJobResponse>> = IntStream.range(0, concurrency)
                .mapToObj {
                    executorService.submit<CreateJobResponse> {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS)) {
                            "Timed out waiting for concurrent start signal"
                        }
                        sendCreateJobRequest(SAME_KEY, IMAGE_URL)
                    }
                }
                .toList()

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            val responses = ArrayList<CreateJobResponse>(concurrency)
            for (future in futures) {
                responses.add(future.get(10, TimeUnit.SECONDS))
            }

            executorService.shutdown()
            assertThat(executorService.awaitTermination(5, TimeUnit.SECONDS)).isTrue()

            val acceptedCount = responses.count { it.httpStatus == 202 }.toLong()
            val replayCount = responses.count { it.httpStatus == 200 }.toLong()
            val unexpectedResponses = responses.filter { it.httpStatus != 200 && it.httpStatus != 202 }

            assertThat(acceptedCount).isEqualTo(1L)
            assertThat(replayCount).isEqualTo(concurrency - 1L)
            assertThat(unexpectedResponses).isEmpty()
            assertThat(responses).allMatch { it.status == "QUEUED" }

            val jobIds = responses.mapNotNull { it.jobId }.toSet()
            assertThat(jobIds).hasSize(1)

            val storedJobs: List<ImageJob> = imageJobRepository.findAll()
            assertThat(storedJobs).hasSize(1)
            assertThat(storedJobs[0].jobId).isEqualTo(jobIds.first())
        } finally {
            executorService.shutdownNow()
        }
    }

    private fun sendCreateJobRequest(idempotencyKey: String, imageUrl: String): CreateJobResponse {
        val requestBody = """
            {
              "imageUrl": "$imageUrl"
            }
        """.trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1/image-jobs"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val jsonNode: JsonNode = objectMapper.readTree(response.body())
        return CreateJobResponse(
            response.statusCode(),
            jsonNode.path("jobId").textValue(),
            jsonNode.path("status").textValue(),
            response.body()
        )
    }

    private data class CreateJobResponse(
        val httpStatus: Int,
        val jobId: String?,
        val status: String?,
        val body: String
    )

    private companion object {
        const val SAME_KEY = "idem-concurrent-1"
        const val IMAGE_URL = "https://example.com/images/input.png"
    }
}

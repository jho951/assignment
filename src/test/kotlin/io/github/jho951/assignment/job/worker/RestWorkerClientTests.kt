package io.github.jho951.assignment.job.worker

import io.github.jho951.assignment.config.WorkerProperties
import io.github.jho951.assignment.job.domain.JobFailureCode
import java.io.IOException
import java.lang.reflect.Method
import java.net.SocketTimeoutException
import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate

class RestWorkerClientTests {

    @Test
    fun shouldResolveIssueKeyUriUnderMockBasePath() {
        val client = newClient(defaultProperties())

        val uri: URI = client.resolveWorkerUri(defaultProperties().issueKeyPath)

        assertThat(uri.toString()).isEqualTo("https://dev.realteeth.ai/mock/auth/issue-key")
    }

    @Test
    fun shouldResolveStatusUriUnderMockBasePath() {
        val client = newClient(defaultProperties())

        val uri = client.resolveWorkerUri("${defaultProperties().processPath}/{jobId}", "job-123")

        assertThat(uri.toString()).isEqualTo("https://dev.realteeth.ai/mock/process/job-123")
    }

    @Test
    fun shouldResolvePathsWithoutLeadingSlashAndTrimTrailingSlash() {
        val properties = WorkerProperties(
            "https://dev.realteeth.ai/mock/",
            "auth/issue-key",
            "process",
            5000,
            "assignment",
            "assignment@example.com"
        )
        val client = newClient(properties)

        val issueKeyUri = client.resolveWorkerUri(properties.issueKeyPath)
        val processUri = client.resolveWorkerUri("${properties.processPath}/{jobId}", "job-456")

        assertThat(issueKeyUri.toString()).isEqualTo("https://dev.realteeth.ai/mock/auth/issue-key")
        assertThat(processUri.toString()).isEqualTo("https://dev.realteeth.ai/mock/process/job-456")
    }

    @Test
    fun shouldIssueApiKeyAndStartProcess() {
        val fixture = fixture(defaultProperties())
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    """
                    {
                      "candidateName": "assignment",
                      "email": "assignment@example.com"
                    }
                    """.trimIndent()
                )
            )
            .andRespond(
                withSuccess(
                    """
                    {
                      "apiKey": "key-1"
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON
                )
            )
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-API-KEY", "key-1"))
            .andExpect(
                content().json(
                    """
                    {
                      "imageUrl": "https://example.com/input.png"
                    }
                    """.trimIndent()
                )
            )
            .andRespond(
                withSuccess(
                    """
                    {
                      "jobId": "worker-1",
                      "status": "PROCESSING"
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON
                )
            )

        val result = fixture.client.startProcess("https://example.com/input.png")

        assertThat(result.workerJobId).isEqualTo("worker-1")
        assertThat(result.status).isEqualTo(WorkerRemoteStatus.PROCESSING)
        fixture.server.verify()
    }

    @Test
    fun shouldReuseCachedApiKeyForSubsequentStatusChecks() {
        val fixture = fixture(defaultProperties())
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
            .andRespond(
                withSuccess(
                    """
                    {
                      "apiKey": "key-cached"
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON
                )
            )
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
            .andExpect(header("X-API-KEY", "key-cached"))
            .andRespond(
                withSuccess(
                    """
                    {
                      "jobId": "worker-2",
                      "status": "PROCESSING"
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON
                )
            )
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process/worker-2"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-API-KEY", "key-cached"))
            .andRespond(
                withSuccess(
                    """
                    {
                      "jobId": "worker-2",
                      "status": "COMPLETED",
                      "result": "https://cdn.example/result.png"
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON
                )
            )

        fixture.client.startProcess("https://example.com/input.png")
        val result = fixture.client.getProcessStatus("worker-2")

        assertThat(result.workerJobId).isEqualTo("worker-2")
        assertThat(result.status).isEqualTo(WorkerRemoteStatus.COMPLETED)
        assertThat(result.result).isEqualTo("https://cdn.example/result.png")
        fixture.server.verify()
    }

    @Test
    fun shouldRefreshApiKeyAfterUnauthorizedAndRetry() {
        val fixture = fixture(defaultProperties())
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
            .andRespond(withSuccess("""{"apiKey":"key-old"}""", MediaType.APPLICATION_JSON))
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
            .andExpect(header("X-API-KEY", "key-old"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
            .andRespond(withSuccess("""{"apiKey":"key-new"}""", MediaType.APPLICATION_JSON))
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
            .andExpect(header("X-API-KEY", "key-new"))
            .andRespond(withSuccess("""{"jobId":"worker-3","status":"COMPLETED"}""", MediaType.APPLICATION_JSON))

        val result = fixture.client.startProcess("https://example.com/input.png")

        assertThat(result.workerJobId).isEqualTo("worker-3")
        assertThat(result.status).isEqualTo(WorkerRemoteStatus.COMPLETED)
        fixture.server.verify()
    }

    @Test
    fun shouldFailWhenRefreshedApiKeyIsRejectedAgain() {
        val fixture = fixture(defaultProperties())
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
            .andRespond(withSuccess("""{"apiKey":"key-old"}""", MediaType.APPLICATION_JSON))
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process/job-unauthorized"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-API-KEY", "key-old"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
            .andRespond(withSuccess("""{"apiKey":"key-new"}""", MediaType.APPLICATION_JSON))
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process/job-unauthorized"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-API-KEY", "key-new"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        assertThatThrownBy { fixture.client.getProcessStatus("job-unauthorized") }
            .isInstanceOfSatisfying(WorkerClientException::class.java) {
                assertThat(it.failureCode).isEqualTo(JobFailureCode.WORKER_AUTH_FAILED)
                assertThat(it.isRetryable()).isFalse()
                assertThat(it).hasMessage("Mock Worker API key was rejected after refresh")
            }
        fixture.server.verify()
    }

    @Test
    fun shouldWrapRefreshFailureAsAuthFailure() {
        val fixture = fixture(defaultProperties())
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
            .andRespond(withSuccess("""{"apiKey":"key-old"}""", MediaType.APPLICATION_JSON))
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
            .andExpect(header("X-API-KEY", "key-old"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertThatThrownBy { fixture.client.startProcess("https://example.com/input.png") }
            .isInstanceOfSatisfying(WorkerClientException::class.java) {
                assertThat(it.failureCode).isEqualTo(JobFailureCode.WORKER_AUTH_FAILED)
                assertThat(it.isRetryable()).isTrue()
                assertThat(it).hasMessage("Failed to refresh Mock Worker API key")
            }
        fixture.server.verify()
    }

    @Test
    fun shouldRejectBlankIssuedApiKey() {
        val fixture = fixture(defaultProperties())
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
            .andRespond(withSuccess("""{"apiKey":""}""", MediaType.APPLICATION_JSON))

        assertThatThrownBy { fixture.client.startProcess("https://example.com/input.png") }
            .isInstanceOfSatisfying(WorkerClientException::class.java) {
                assertThat(it.failureCode).isEqualTo(JobFailureCode.WORKER_AUTH_FAILED)
                assertThat(it.isRetryable()).isTrue()
                assertThat(it).hasMessage("Mock Worker did not return a valid API key")
            }
        fixture.server.verify()
    }

    @Test
    fun shouldRejectInvalidStartResponse() {
        val fixture = fixture(defaultProperties())
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
            .andRespond(withSuccess("""{"apiKey":"key-1"}""", MediaType.APPLICATION_JSON))
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
            .andExpect(header("X-API-KEY", "key-1"))
            .andRespond(withSuccess("""{"jobId":"worker-1"}""", MediaType.APPLICATION_JSON))

        assertThatThrownBy { fixture.client.startProcess("https://example.com/input.png") }
            .isInstanceOfSatisfying(WorkerClientException::class.java) {
                assertThat(it.failureCode).isEqualTo(JobFailureCode.INTERNAL_ERROR)
                assertThat(it.isRetryable()).isFalse()
                assertThat(it).hasMessage("Mock Worker returned an invalid start response")
            }
        fixture.server.verify()
    }

    @Test
    fun shouldRejectInvalidStatusResponse() {
        val fixture = fixture(defaultProperties())
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
            .andRespond(withSuccess("""{"apiKey":"key-1"}""", MediaType.APPLICATION_JSON))
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process/job-invalid-status"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-API-KEY", "key-1"))
            .andRespond(withSuccess("""{"jobId":"job-invalid-status"}""", MediaType.APPLICATION_JSON))

        assertThatThrownBy { fixture.client.getProcessStatus("job-invalid-status") }
            .isInstanceOfSatisfying(WorkerClientException::class.java) {
                assertThat(it.failureCode).isEqualTo(JobFailureCode.INTERNAL_ERROR)
                assertThat(it.isRetryable()).isFalse()
                assertThat(it).hasMessage("Mock Worker returned an invalid status response")
            }
        fixture.server.verify()
    }

    @Test
    fun shouldMapServerErrorToRetryableUnavailable() {
        val fixture = fixture(defaultProperties())
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
            .andRespond(withSuccess("""{"apiKey":"key-1"}""", MediaType.APPLICATION_JSON))
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
            .andExpect(header("X-API-KEY", "key-1"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertThatThrownBy { fixture.client.startProcess("https://example.com/input.png") }
            .isInstanceOfSatisfying(WorkerClientException::class.java) {
                assertThat(it.failureCode).isEqualTo(JobFailureCode.WORKER_UNAVAILABLE)
                assertThat(it.isRetryable()).isTrue()
                assertThat(it).hasMessage("Mock Worker returned 503")
            }
        fixture.server.verify()
    }

    @Test
    fun shouldMapClientErrorToNonRetryableBadRequest() {
        val fixture = fixture(defaultProperties())
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
            .andRespond(withSuccess("""{"apiKey":"key-1"}""", MediaType.APPLICATION_JSON))
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
            .andExpect(header("X-API-KEY", "key-1"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST))

        assertThatThrownBy { fixture.client.startProcess("https://example.com/input.png") }
            .isInstanceOfSatisfying(WorkerClientException::class.java) {
                assertThat(it.failureCode).isEqualTo(JobFailureCode.WORKER_BAD_REQUEST)
                assertThat(it.isRetryable()).isFalse()
                assertThat(it).hasMessage("Mock Worker returned 400")
            }
        fixture.server.verify()
    }

    @Test
    fun shouldMapSocketTimeoutToWorkerTimeout() {
        val client = newClient(defaultProperties())

        val workerClientException = invokeMapException(
            client,
            ResourceAccessException("read timeout", SocketTimeoutException("Read timed out"))
        )

        assertThat(workerClientException.failureCode).isEqualTo(JobFailureCode.WORKER_TIMEOUT)
        assertThat(workerClientException.isRetryable()).isTrue()
        assertThat(workerClientException).hasMessage("Mock Worker request timed out")
    }

    @Test
    fun shouldMapTimedOutMessageToWorkerTimeout() {
        val client = newClient(defaultProperties())

        val workerClientException = invokeMapException(
            client,
            ResourceAccessException("read timeout", IOException("Connection timed out"))
        )

        assertThat(workerClientException.failureCode).isEqualTo(JobFailureCode.WORKER_TIMEOUT)
        assertThat(workerClientException.isRetryable()).isTrue()
    }

    @Test
    fun shouldMapGenericResourceAccessToUnavailable() {
        val client = newClient(defaultProperties())

        val workerClientException = invokeMapException(
            client,
            ResourceAccessException("connect refused", IOException("Connection refused"))
        )

        assertThat(workerClientException.failureCode).isEqualTo(JobFailureCode.WORKER_UNAVAILABLE)
        assertThat(workerClientException.isRetryable()).isTrue()
        assertThat(workerClientException).hasMessage("Mock Worker is unavailable")
    }

    @Test
    fun shouldMapUnexpectedRestClientExceptionToInternalError() {
        val client = newClient(defaultProperties())

        val workerClientException = invokeMapException(
            client,
            RestClientException("boom")
        )

        assertThat(workerClientException.failureCode).isEqualTo(JobFailureCode.INTERNAL_ERROR)
        assertThat(workerClientException.isRetryable()).isFalse()
        assertThat(workerClientException).hasMessage("Unexpected Worker client error")
    }

    private fun invokeMapException(client: RestWorkerClient, exception: RestClientException): WorkerClientException {
        val method: Method = RestWorkerClient::class.java.getDeclaredMethod("mapException", RestClientException::class.java)
        method.isAccessible = true
        return method.invoke(client, exception) as WorkerClientException
    }

    private fun newClient(properties: WorkerProperties): RestWorkerClient =
        RestWorkerClient(RestClient.builder().build(), properties)

    private fun fixture(properties: WorkerProperties): Fixture {
        val restTemplate = RestTemplate()
        val server = MockRestServiceServer.bindTo(restTemplate).build()
        val client = RestWorkerClient(RestClient.create(restTemplate), properties)
        return Fixture(client, server)
    }

    private fun defaultProperties(): WorkerProperties =
        WorkerProperties(
            "https://dev.realteeth.ai/mock",
            "/auth/issue-key",
            "/process",
            5000,
            "assignment",
            "assignment@example.com"
        )

    private data class Fixture(
        val client: RestWorkerClient,
        val server: MockRestServiceServer
    )
}

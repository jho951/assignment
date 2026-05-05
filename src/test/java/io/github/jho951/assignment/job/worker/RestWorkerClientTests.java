package io.github.jho951.assignment.job.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.lang.reflect.Method;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import io.github.jho951.assignment.config.WorkerProperties;
import io.github.jho951.assignment.job.domain.JobFailureCode;

class RestWorkerClientTests {

    @Test
    void shouldResolveIssueKeyUriUnderMockBasePath() {
        RestWorkerClient client = newClient(defaultProperties());

        URI uri = client.resolveWorkerUri(defaultProperties().issueKeyPath());

        assertThat(uri.toString()).isEqualTo("https://dev.realteeth.ai/mock/auth/issue-key");
    }

    @Test
    void shouldResolveStatusUriUnderMockBasePath() {
        RestWorkerClient client = newClient(defaultProperties());

        URI uri = client.resolveWorkerUri(defaultProperties().processPath() + "/{jobId}", "job-123");

        assertThat(uri.toString()).isEqualTo("https://dev.realteeth.ai/mock/process/job-123");
    }

    @Test
    void shouldResolvePathsWithoutLeadingSlashAndTrimTrailingSlash() {
        WorkerProperties properties = new WorkerProperties(
                "https://dev.realteeth.ai/mock/",
                "auth/issue-key",
                "process",
                5000,
                "assignment",
                "assignment@example.com"
        );
        RestWorkerClient client = newClient(properties);

        URI issueKeyUri = client.resolveWorkerUri(properties.issueKeyPath());
        URI processUri = client.resolveWorkerUri(properties.processPath() + "/{jobId}", "job-456");

        assertThat(issueKeyUri.toString()).isEqualTo("https://dev.realteeth.ai/mock/auth/issue-key");
        assertThat(processUri.toString()).isEqualTo("https://dev.realteeth.ai/mock/process/job-456");
    }

    @Test
    void shouldIssueApiKeyAndStartProcess() {
        Fixture fixture = fixture(defaultProperties());
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "candidateName": "assignment",
                          "email": "assignment@example.com"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "apiKey": "key-1"
                        }
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-KEY", "key-1"))
                .andExpect(content().json("""
                        {
                          "imageUrl": "https://example.com/input.png"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "jobId": "worker-1",
                          "status": "PROCESSING"
                        }
                        """, MediaType.APPLICATION_JSON));

        WorkerStartResult result = fixture.client.startProcess("https://example.com/input.png");

        assertThat(result.workerJobId()).isEqualTo("worker-1");
        assertThat(result.status()).isEqualTo(WorkerRemoteStatus.PROCESSING);
        fixture.server.verify();
    }

    @Test
    void shouldReuseCachedApiKeyForSubsequentStatusChecks() {
        Fixture fixture = fixture(defaultProperties());
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
                .andRespond(withSuccess("""
                        {
                          "apiKey": "key-cached"
                        }
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
                .andExpect(header("X-API-KEY", "key-cached"))
                .andRespond(withSuccess("""
                        {
                          "jobId": "worker-2",
                          "status": "PROCESSING"
                        }
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process/worker-2"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-KEY", "key-cached"))
                .andRespond(withSuccess("""
                        {
                          "jobId": "worker-2",
                          "status": "COMPLETED",
                          "result": "https://cdn.example/result.png"
                        }
                        """, MediaType.APPLICATION_JSON));

        fixture.client.startProcess("https://example.com/input.png");
        WorkerStatusResult result = fixture.client.getProcessStatus("worker-2");

        assertThat(result.workerJobId()).isEqualTo("worker-2");
        assertThat(result.status()).isEqualTo(WorkerRemoteStatus.COMPLETED);
        assertThat(result.result()).isEqualTo("https://cdn.example/result.png");
        fixture.server.verify();
    }

    @Test
    void shouldRefreshApiKeyAfterUnauthorizedAndRetry() {
        Fixture fixture = fixture(defaultProperties());
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
                .andRespond(withSuccess("""
                        {
                          "apiKey": "key-old"
                        }
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
                .andExpect(header("X-API-KEY", "key-old"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
                .andRespond(withSuccess("""
                        {
                          "apiKey": "key-new"
                        }
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
                .andExpect(header("X-API-KEY", "key-new"))
                .andRespond(withSuccess("""
                        {
                          "jobId": "worker-3",
                          "status": "COMPLETED"
                        }
                        """, MediaType.APPLICATION_JSON));

        WorkerStartResult result = fixture.client.startProcess("https://example.com/input.png");

        assertThat(result.workerJobId()).isEqualTo("worker-3");
        assertThat(result.status()).isEqualTo(WorkerRemoteStatus.COMPLETED);
        fixture.server.verify();
    }

    @Test
    void shouldFailWhenRefreshedApiKeyIsRejectedAgain() {
        Fixture fixture = fixture(defaultProperties());
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
                .andRespond(withSuccess("""
                        {
                          "apiKey": "key-old"
                        }
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process/job-unauthorized"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-KEY", "key-old"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
                .andRespond(withSuccess("""
                        {
                          "apiKey": "key-new"
                        }
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process/job-unauthorized"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-KEY", "key-new"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> fixture.client.getProcessStatus("job-unauthorized"))
                .isInstanceOf(WorkerClientException.class)
                .satisfies(exception -> {
                    WorkerClientException workerClientException = (WorkerClientException) exception;
                    assertThat(workerClientException.getFailureCode()).isEqualTo(JobFailureCode.WORKER_AUTH_FAILED);
                    assertThat(workerClientException.isRetryable()).isFalse();
                    assertThat(workerClientException).hasMessage("Mock Worker API key was rejected after refresh");
                });
        fixture.server.verify();
    }

    @Test
    void shouldWrapRefreshFailureAsAuthFailure() {
        Fixture fixture = fixture(defaultProperties());
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
                .andRespond(withSuccess("""
                        {
                          "apiKey": "key-old"
                        }
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
                .andExpect(header("X-API-KEY", "key-old"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> fixture.client.startProcess("https://example.com/input.png"))
                .isInstanceOf(WorkerClientException.class)
                .satisfies(exception -> {
                    WorkerClientException workerClientException = (WorkerClientException) exception;
                    assertThat(workerClientException.getFailureCode()).isEqualTo(JobFailureCode.WORKER_AUTH_FAILED);
                    assertThat(workerClientException.isRetryable()).isTrue();
                    assertThat(workerClientException).hasMessage("Failed to refresh Mock Worker API key");
                });
        fixture.server.verify();
    }

    @Test
    void shouldRejectBlankIssuedApiKey() {
        Fixture fixture = fixture(defaultProperties());
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
                .andRespond(withSuccess("""
                        {
                          "apiKey": ""
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.startProcess("https://example.com/input.png"))
                .isInstanceOf(WorkerClientException.class)
                .satisfies(exception -> {
                    WorkerClientException workerClientException = (WorkerClientException) exception;
                    assertThat(workerClientException.getFailureCode()).isEqualTo(JobFailureCode.WORKER_AUTH_FAILED);
                    assertThat(workerClientException.isRetryable()).isTrue();
                    assertThat(workerClientException).hasMessage("Mock Worker did not return a valid API key");
                });
        fixture.server.verify();
    }

    @Test
    void shouldRejectInvalidStartResponse() {
        Fixture fixture = fixture(defaultProperties());
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
                .andRespond(withSuccess("""
                        {
                          "apiKey": "key-1"
                        }
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
                .andExpect(header("X-API-KEY", "key-1"))
                .andRespond(withSuccess("""
                        {
                          "jobId": "worker-1"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.startProcess("https://example.com/input.png"))
                .isInstanceOf(WorkerClientException.class)
                .satisfies(exception -> {
                    WorkerClientException workerClientException = (WorkerClientException) exception;
                    assertThat(workerClientException.getFailureCode()).isEqualTo(JobFailureCode.INTERNAL_ERROR);
                    assertThat(workerClientException.isRetryable()).isFalse();
                    assertThat(workerClientException).hasMessage("Mock Worker returned an invalid start response");
                });
        fixture.server.verify();
    }

    @Test
    void shouldRejectInvalidStatusResponse() {
        Fixture fixture = fixture(defaultProperties());
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
                .andRespond(withSuccess("""
                        {
                          "apiKey": "key-1"
                        }
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process/job-invalid-status"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-KEY", "key-1"))
                .andRespond(withSuccess("""
                        {
                          "jobId": "job-invalid-status"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.getProcessStatus("job-invalid-status"))
                .isInstanceOf(WorkerClientException.class)
                .satisfies(exception -> {
                    WorkerClientException workerClientException = (WorkerClientException) exception;
                    assertThat(workerClientException.getFailureCode()).isEqualTo(JobFailureCode.INTERNAL_ERROR);
                    assertThat(workerClientException.isRetryable()).isFalse();
                    assertThat(workerClientException).hasMessage("Mock Worker returned an invalid status response");
                });
        fixture.server.verify();
    }

    @Test
    void shouldMapServerErrorToRetryableUnavailable() {
        Fixture fixture = fixture(defaultProperties());
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
                .andRespond(withSuccess("""
                        {
                          "apiKey": "key-1"
                        }
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
                .andExpect(header("X-API-KEY", "key-1"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> fixture.client.startProcess("https://example.com/input.png"))
                .isInstanceOf(WorkerClientException.class)
                .satisfies(exception -> {
                    WorkerClientException workerClientException = (WorkerClientException) exception;
                    assertThat(workerClientException.getFailureCode()).isEqualTo(JobFailureCode.WORKER_UNAVAILABLE);
                    assertThat(workerClientException.isRetryable()).isTrue();
                    assertThat(workerClientException).hasMessage("Mock Worker returned 503");
                });
        fixture.server.verify();
    }

    @Test
    void shouldMapClientErrorToNonRetryableBadRequest() {
        Fixture fixture = fixture(defaultProperties());
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/auth/issue-key"))
                .andRespond(withSuccess("""
                        {
                          "apiKey": "key-1"
                        }
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://dev.realteeth.ai/mock/process"))
                .andExpect(header("X-API-KEY", "key-1"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> fixture.client.startProcess("https://example.com/input.png"))
                .isInstanceOf(WorkerClientException.class)
                .satisfies(exception -> {
                    WorkerClientException workerClientException = (WorkerClientException) exception;
                    assertThat(workerClientException.getFailureCode()).isEqualTo(JobFailureCode.WORKER_BAD_REQUEST);
                    assertThat(workerClientException.isRetryable()).isFalse();
                    assertThat(workerClientException).hasMessage("Mock Worker returned 400");
                });
        fixture.server.verify();
    }

    @Test
    void shouldMapSocketTimeoutToWorkerTimeout() throws Exception {
        RestWorkerClient client = newClient(defaultProperties());

        WorkerClientException workerClientException = invokeMapException(
                client,
                new ResourceAccessException("read timeout", new SocketTimeoutException("Read timed out"))
        );

        assertThat(workerClientException.getFailureCode()).isEqualTo(JobFailureCode.WORKER_TIMEOUT);
        assertThat(workerClientException.isRetryable()).isTrue();
        assertThat(workerClientException).hasMessage("Mock Worker request timed out");
    }

    @Test
    void shouldMapTimedOutMessageToWorkerTimeout() throws Exception {
        RestWorkerClient client = newClient(defaultProperties());

        WorkerClientException workerClientException = invokeMapException(
                client,
                new ResourceAccessException("read timeout", new IOException("Connection timed out"))
        );

        assertThat(workerClientException.getFailureCode()).isEqualTo(JobFailureCode.WORKER_TIMEOUT);
        assertThat(workerClientException.isRetryable()).isTrue();
    }

    @Test
    void shouldMapGenericResourceAccessToUnavailable() throws Exception {
        RestWorkerClient client = newClient(defaultProperties());

        WorkerClientException workerClientException = invokeMapException(
                client,
                new ResourceAccessException("connect refused", new IOException("Connection refused"))
        );

        assertThat(workerClientException.getFailureCode()).isEqualTo(JobFailureCode.WORKER_UNAVAILABLE);
        assertThat(workerClientException.isRetryable()).isTrue();
        assertThat(workerClientException).hasMessage("Mock Worker is unavailable");
    }

    @Test
    void shouldMapUnexpectedRestClientExceptionToInternalError() throws Exception {
        RestWorkerClient client = newClient(defaultProperties());

        WorkerClientException workerClientException = invokeMapException(
                client,
                new RestClientException("boom")
        );

        assertThat(workerClientException.getFailureCode()).isEqualTo(JobFailureCode.INTERNAL_ERROR);
        assertThat(workerClientException.isRetryable()).isFalse();
        assertThat(workerClientException).hasMessage("Unexpected Worker client error");
    }

    private WorkerClientException invokeMapException(RestWorkerClient client, RestClientException exception) throws Exception {
        Method method = RestWorkerClient.class.getDeclaredMethod("mapException", RestClientException.class);
        method.setAccessible(true);
        return (WorkerClientException) method.invoke(client, exception);
    }

    private RestWorkerClient newClient(WorkerProperties properties) {
        return new RestWorkerClient(RestClient.builder().build(), properties);
    }

    private Fixture fixture(WorkerProperties properties) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        RestWorkerClient client = new RestWorkerClient(RestClient.create(restTemplate), properties);
        return new Fixture(client, server);
    }

    private WorkerProperties defaultProperties() {
        return new WorkerProperties(
                "https://dev.realteeth.ai/mock",
                "/auth/issue-key",
                "/process",
                5000,
                "assignment",
                "assignment@example.com"
        );
    }

    private record Fixture(
            RestWorkerClient client,
            MockRestServiceServer server
    ) {
    }
}

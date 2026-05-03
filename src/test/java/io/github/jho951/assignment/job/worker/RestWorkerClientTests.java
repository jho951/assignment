package io.github.jho951.assignment.job.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import io.github.jho951.assignment.config.WorkerProperties;

class RestWorkerClientTests {

    @Test
    void shouldResolveIssueKeyUriUnderMockBasePath() {
        WorkerProperties properties = new WorkerProperties(
                "https://dev.realteeth.ai/mock",
                "/auth/issue-key",
                "/process",
                5000,
                "assignment",
                "assignment@example.com"
        );
        RestWorkerClient client = new RestWorkerClient(RestClient.builder().build(), properties);

        URI uri = client.resolveWorkerUri(properties.issueKeyPath());

        assertThat(uri.toString()).isEqualTo("https://dev.realteeth.ai/mock/auth/issue-key");
    }

    @Test
    void shouldResolveStatusUriUnderMockBasePath() {
        WorkerProperties properties = new WorkerProperties(
                "https://dev.realteeth.ai/mock",
                "/auth/issue-key",
                "/process",
                5000,
                "assignment",
                "assignment@example.com"
        );
        RestWorkerClient client = new RestWorkerClient(RestClient.builder().build(), properties);

        URI uri = client.resolveWorkerUri(properties.processPath() + "/{jobId}", "job-123");

        assertThat(uri.toString()).isEqualTo("https://dev.realteeth.ai/mock/process/job-123");
    }
}

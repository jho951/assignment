package io.github.jho951.assignment.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

class ApplicationCoreConfigurationTests {

    private final ApplicationCoreConfiguration applicationCoreConfiguration = new ApplicationCoreConfiguration();

    @Test
    void shouldCreateUtcClock() {
        assertThat(applicationCoreConfiguration.clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void shouldCreateJobTaskExecutorFromProperties() {
        JobProperties jobProperties = new JobProperties(
                3,
                1_000,
                10,
                5_000,
                60_000,
                false,
                new JobProperties.Executor(2, 4, 6)
        );

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) applicationCoreConfiguration.jobTaskExecutor(jobProperties);

        try {
            assertThat(executor.getThreadNamePrefix()).isEqualTo("job-worker-");
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(6);
        }
        finally {
            executor.shutdown();
        }
    }

    @Test
    void shouldCreateWorkerRestClient() {
        WorkerProperties workerProperties = new WorkerProperties(
                "https://dev.realteeth.ai/mock",
                "/auth/issue-key",
                "/process",
                5_000,
                "assignment",
                "assignment@example.com"
        );

        RestClient restClient = applicationCoreConfiguration.workerRestClient(workerProperties);

        assertThat(restClient).isNotNull();
    }
}

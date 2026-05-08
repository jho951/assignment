package io.github.jho951.assignment.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

class ApplicationCoreConfigurationTests {

    private final ApplicationCoreConfiguration configuration = new ApplicationCoreConfiguration();

    @Test
    void shouldCreateUtcClock() {
        assertThat(configuration.clock().getZone()).isEqualTo(ZoneOffset.UTC);
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
            new JobProperties.ExecutorProperties(2, 4, 6)
        );

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) configuration.jobTaskExecutor(jobProperties);

        try {
            assertThat(executor.getThreadNamePrefix()).isEqualTo("stock-order-worker-");
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(6);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shouldCreateBrokerageRestClient() {
        BrokerageProperties properties = new BrokerageProperties(
            "https://brokerage.example",
            "/oauth2/token",
            "/v1/orders",
            "/v1/orders/{orderId}",
            5_000,
            "demo-app-key",
            "demo-app-secret",
            "assignment-client"
        );

        RestClient restClient = configuration.brokerageRestClient(properties);

        assertThat(restClient).isNotNull();
    }
}

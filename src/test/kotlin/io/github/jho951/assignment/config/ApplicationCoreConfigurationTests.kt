package io.github.jho951.assignment.config

import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.web.client.RestClient

class ApplicationCoreConfigurationTests {

    private val applicationCoreConfiguration = ApplicationCoreConfiguration()

    @Test
    fun shouldCreateUtcClock() {
        assertThat(applicationCoreConfiguration.clock().zone).isEqualTo(ZoneOffset.UTC)
    }

    @Test
    fun shouldCreateJobTaskExecutorFromProperties() {
        val jobProperties = JobProperties(
            3,
            1_000,
            10,
            5_000,
            60_000,
            false,
            JobProperties.Executor(2, 4, 6)
        )

        val executor = applicationCoreConfiguration.jobTaskExecutor(jobProperties) as ThreadPoolTaskExecutor

        try {
            assertThat(executor.threadNamePrefix).isEqualTo("job-worker-")
            assertThat(executor.corePoolSize).isEqualTo(2)
            assertThat(executor.maxPoolSize).isEqualTo(4)
            assertThat(executor.threadPoolExecutor.queue.remainingCapacity()).isEqualTo(6)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun shouldCreateWorkerRestClient() {
        val workerProperties = WorkerProperties(
            "https://dev.realteeth.ai/mock",
            "/auth/issue-key",
            "/process",
            5_000,
            "assignment",
            "assignment@example.com"
        )

        val restClient: RestClient = applicationCoreConfiguration.workerRestClient(workerProperties)

        assertThat(restClient).isNotNull()
    }
}

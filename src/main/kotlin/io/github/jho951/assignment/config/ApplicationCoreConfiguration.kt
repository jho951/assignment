package io.github.jho951.assignment.config

import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.web.client.RestClient

@Configuration
@EnableScheduling
class ApplicationCoreConfiguration {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun jobTaskExecutor(jobProperties: JobProperties): Executor {
        val executorProperties = jobProperties.executor
        val executor = ThreadPoolTaskExecutor()
        executor.setThreadNamePrefix("job-worker-")
        executor.setCorePoolSize(executorProperties.coreSize)
        executor.setMaxPoolSize(executorProperties.maxSize)
        executor.setQueueCapacity(executorProperties.queueCapacity)
        executor.initialize()
        return executor
    }

    @Bean
    fun workerRestClient(workerProperties: WorkerProperties): RestClient {
        val requestFactory = SimpleClientHttpRequestFactory()
        val timeout = Duration.ofMillis(workerProperties.timeoutMs)
        requestFactory.setConnectTimeout(timeout)
        requestFactory.setReadTimeout(timeout)

        return RestClient.builder()
            .requestFactory(requestFactory)
            .build()
    }
}

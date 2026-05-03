package io.github.jho951.assignment.config;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling
public class ApplicationCoreConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Executor jobTaskExecutor(JobProperties jobProperties) {
        JobProperties.Executor executorProperties = jobProperties.executor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("job-worker-");
        executor.setCorePoolSize(executorProperties.coreSize());
        executor.setMaxPoolSize(executorProperties.maxSize());
        executor.setQueueCapacity(executorProperties.queueCapacity());
        executor.initialize();
        return executor;
    }

    @Bean
    RestClient workerRestClient(WorkerProperties workerProperties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(workerProperties.timeoutMs());
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}

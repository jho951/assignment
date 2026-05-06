package io.github.jho951.assignment

import io.github.jho951.assignment.config.WorkerProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "jobs.scheduling-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:assignment-context-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password="
    ]
)
class AssignmentApplicationTests {

    @Autowired
    private lateinit var workerProperties: WorkerProperties

    @Test
    fun contextLoads() {
    }

    @Test
    fun shouldUseDocumentedDefaultWorkerIssueKeyPath() {
        assertThat(workerProperties.issueKeyPath)
            .isEqualTo("mock_2e80116bf37a4505aac959068b5ca051")
    }
}

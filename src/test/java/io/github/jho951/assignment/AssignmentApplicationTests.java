package io.github.jho951.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.github.jho951.assignment.config.WorkerProperties;

@SpringBootTest(properties = {
		"jobs.scheduling-enabled=false",
		"spring.datasource.url=jdbc:h2:mem:assignment-context-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
		"spring.datasource.username=sa",
		"spring.datasource.password="
})
class AssignmentApplicationTests {

	@Autowired
	private WorkerProperties workerProperties;

	@Test
	void contextLoads() {}

	@Test
	void shouldUseDocumentedDefaultWorkerIssueKeyPath() {
		assertThat(workerProperties.issueKeyPath()).isEqualTo("/auth/issue-key");
	}

}

package io.github.jho951.assignment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
        "jobs.scheduling-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:assignment-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password="
    }
)
class AssignmentApplicationTests {

    @Test
    void contextLoads() {
    }
}

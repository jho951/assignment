package io.github.jho951.assignment.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration {

    @Bean
    fun assignmentOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("Assignment Image Job API")
                .version("1.0.0")
                .description("Public API for asynchronous image job creation, status lookup, result lookup, and job listing.")
                .contact(
                    Contact()
                        .name("Assignment API")
                        .email("assignment@example.com")
                )
        )
}

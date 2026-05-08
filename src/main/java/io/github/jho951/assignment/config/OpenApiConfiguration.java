package io.github.jho951.assignment.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI assignmentOpenApi() {
        return new OpenAPI().info(
            new Info()
                .title("Assignment Stock Order API")
                .version("1.0.0")
                .description("Public API for asynchronous stock order submission, status lookup, result lookup, and job listing.")
                .contact(
                    new Contact()
                        .name("Assignment API")
                        .email("assignment@example.com")
                )
        );
    }
}

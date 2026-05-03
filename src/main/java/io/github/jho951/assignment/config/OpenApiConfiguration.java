package io.github.jho951.assignment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI assignmentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Assignment Image Job API")
                        .version("1.0.0")
                        .description("Public API for asynchronous image job creation, status lookup, result lookup, and job listing.")
                        .contact(new Contact()
                                .name("Assignment API")
                                .email("assignment@example.com")));
    }
}

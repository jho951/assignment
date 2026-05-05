package io.github.jho951.assignment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.models.OpenAPI;

class OpenApiConfigurationTests {

    private final OpenApiConfiguration openApiConfiguration = new OpenApiConfiguration();

    @Test
    void shouldBuildAssignmentOpenApiMetadata() {
        OpenAPI openAPI = openApiConfiguration.assignmentOpenApi();

        assertThat(openAPI.getInfo()).isNotNull();
        assertThat(openAPI.getInfo().getTitle()).isEqualTo("Assignment Image Job API");
        assertThat(openAPI.getInfo().getVersion()).isEqualTo("1.0.0");
        assertThat(openAPI.getInfo().getDescription())
                .contains("asynchronous image job creation")
                .contains("job listing");
        assertThat(openAPI.getInfo().getContact().getName()).isEqualTo("Assignment API");
        assertThat(openAPI.getInfo().getContact().getEmail()).isEqualTo("assignment@example.com");
    }
}

package io.github.jho951.assignment.config

import io.swagger.v3.oas.models.OpenAPI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiConfigurationTests {

    private val openApiConfiguration = OpenApiConfiguration()

    @Test
    fun shouldBuildAssignmentOpenApiMetadata() {
        val openAPI: OpenAPI = openApiConfiguration.assignmentOpenApi()

        assertThat(openAPI.info).isNotNull()
        assertThat(openAPI.info.title).isEqualTo("Assignment Image Job API")
        assertThat(openAPI.info.version).isEqualTo("1.0.0")
        assertThat(openAPI.info.description)
            .contains("asynchronous image job creation")
            .contains("job listing")
        assertThat(openAPI.info.contact.name).isEqualTo("Assignment API")
        assertThat(openAPI.info.contact.email).isEqualTo("assignment@example.com")
    }
}

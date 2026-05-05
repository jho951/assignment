package io.github.jho951.assignment

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class AssignmentApplication

fun main(args: Array<String>) {
    runApplication<AssignmentApplication>(*args)
}

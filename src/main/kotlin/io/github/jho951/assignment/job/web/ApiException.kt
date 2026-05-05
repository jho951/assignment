package io.github.jho951.assignment.job.web

import io.github.jho951.assignment.job.domain.JobFailureCode
import org.springframework.http.HttpStatus

class ApiException(
    val httpStatus: HttpStatus,
    val code: JobFailureCode,
    message: String
) : RuntimeException(message)

package io.github.jho951.assignment.job.worker

import io.github.jho951.assignment.config.WorkerProperties
import io.github.jho951.assignment.job.domain.JobFailureCode
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.util.StringUtils
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder

@Component
class RestWorkerClient(
    private val restClient: RestClient,
    private val workerProperties: WorkerProperties
) : WorkerClient {

    private val cachedApiKey = AtomicReference<String>()

    override fun startProcess(imageUrl: String): WorkerStartResult =
        withAuthorizedCall { apiKey ->
            val response = restClient.post()
                .uri(resolveWorkerUri(workerProperties.processPath))
                .header("X-API-KEY", apiKey)
                .body(ProcessRequest(imageUrl))
                .retrieve()
                .body(ProcessStartResponse::class.java)

            if (response == null || response.jobId == null || response.status == null) {
                throw WorkerClientException(
                    JobFailureCode.INTERNAL_ERROR,
                    false,
                    "Mock Worker returned an invalid start response"
                )
            }

            WorkerStartResult(response.jobId, WorkerRemoteStatus.valueOf(response.status))
        }

    override fun getProcessStatus(workerJobId: String): WorkerStatusResult =
        withAuthorizedCall { apiKey ->
            val response = restClient.get()
                .uri(resolveWorkerUri("${workerProperties.processPath}/{jobId}", workerJobId))
                .header("X-API-KEY", apiKey)
                .retrieve()
                .body(ProcessStatusResponse::class.java)

            if (response == null || response.jobId == null || response.status == null) {
                throw WorkerClientException(
                    JobFailureCode.INTERNAL_ERROR,
                    false,
                    "Mock Worker returned an invalid status response"
                )
            }

            WorkerStatusResult(response.jobId, WorkerRemoteStatus.valueOf(response.status), response.result)
        }

    fun resolveWorkerUri(pathTemplate: String, vararg uriVariables: Any): URI {
        val normalizedBaseUrl = StringUtils.trimTrailingCharacter(workerProperties.baseUrl, '/')
        val normalizedPath = if (pathTemplate.startsWith("/")) pathTemplate else "/$pathTemplate"
        return UriComponentsBuilder.fromUriString(normalizedBaseUrl)
            .path(normalizedPath)
            .buildAndExpand(*uriVariables)
            .toUri()
    }

    private fun <T> withAuthorizedCall(authorizedCall: (String) -> T): T {
        val apiKey = getOrIssueApiKey()
        try {
            return authorizedCall(apiKey)
        } catch (exception: RestClientException) {
            val mappedException = mapException(exception)
            if (mappedException !is UnauthorizedWorkerException) {
                throw mappedException
            }

            cachedApiKey.set(null)
            val refreshedApiKey = try {
                issueApiKey()
            } catch (issueKeyFailure: WorkerClientException) {
                throw WorkerClientException(
                    JobFailureCode.WORKER_AUTH_FAILED,
                    issueKeyFailure.isRetryable(),
                    "Failed to refresh Mock Worker API key",
                    issueKeyFailure
                )
            }

            try {
                return authorizedCall(refreshedApiKey)
            } catch (retryException: RestClientException) {
                val retriedException = mapException(retryException)
                if (retriedException is UnauthorizedWorkerException) {
                    throw WorkerClientException(
                        JobFailureCode.WORKER_AUTH_FAILED,
                        false,
                        "Mock Worker API key was rejected after refresh",
                        retriedException
                    )
                }
                throw retriedException
            }
        }
    }

    private fun getOrIssueApiKey(): String {
        val currentKey = cachedApiKey.get()
        if (!currentKey.isNullOrBlank()) {
            return currentKey
        }
        synchronized(cachedApiKey) {
            val cachedValue = cachedApiKey.get()
            if (!cachedValue.isNullOrBlank()) {
                return cachedValue
            }
            return issueApiKey()
        }
    }

    private fun issueApiKey(): String {
        try {
            val response = restClient.post()
                .uri(resolveWorkerUri(workerProperties.issueKeyPath))
                .body(IssueKeyRequest(workerProperties.candidateName, workerProperties.candidateEmail))
                .retrieve()
                .body(IssueKeyResponse::class.java)

            if (response == null || response.apiKey.isNullOrBlank()) {
                throw WorkerClientException(
                    JobFailureCode.WORKER_AUTH_FAILED,
                    true,
                    "Mock Worker did not return a valid API key"
                )
            }

            cachedApiKey.set(response.apiKey)
            return response.apiKey
        } catch (exception: RestClientException) {
            throw mapException(exception)
        }
    }

    private fun mapException(exception: RestClientException): WorkerClientException {
        if (exception is RestClientResponseException) {
            val statusCode: HttpStatusCode = exception.statusCode
            val value = statusCode.value()
            if (value == 401) {
                return UnauthorizedWorkerException("Mock Worker API key was rejected", exception)
            }
            if (value == 429 || value >= 500) {
                return WorkerClientException(
                    JobFailureCode.WORKER_UNAVAILABLE,
                    true,
                    "Mock Worker returned $value",
                    exception
                )
            }
            return WorkerClientException(
                JobFailureCode.WORKER_BAD_REQUEST,
                false,
                "Mock Worker returned $value",
                exception
            )
        }

        if (exception is ResourceAccessException) {
            val rootCause = exception.mostSpecificCause
            if (rootCause is SocketTimeoutException || containsTimeout(rootCause)) {
                return WorkerClientException(
                    JobFailureCode.WORKER_TIMEOUT,
                    true,
                    "Mock Worker request timed out",
                    exception
                )
            }
            return WorkerClientException(
                JobFailureCode.WORKER_UNAVAILABLE,
                true,
                "Mock Worker is unavailable",
                exception
            )
        }

        return WorkerClientException(
            JobFailureCode.INTERNAL_ERROR,
            false,
            "Unexpected Worker client error",
            exception
        )
    }

    private fun containsTimeout(throwable: Throwable?): Boolean =
        throwable?.message?.lowercase()?.contains("timed out") == true

    @JvmRecord
    private data class IssueKeyRequest(
        val candidateName: String,
        val email: String
    )

    @JvmRecord
    private data class IssueKeyResponse(
        val apiKey: String?
    )

    @JvmRecord
    private data class ProcessRequest(
        val imageUrl: String
    )

    @JvmRecord
    private data class ProcessStartResponse(
        val jobId: String?,
        val status: String?
    )

    @JvmRecord
    private data class ProcessStatusResponse(
        val jobId: String?,
        val status: String?,
        val result: String?
    )

    private class UnauthorizedWorkerException(
        message: String,
        cause: Throwable
    ) : WorkerClientException(JobFailureCode.WORKER_AUTH_FAILED, true, message, cause)
}

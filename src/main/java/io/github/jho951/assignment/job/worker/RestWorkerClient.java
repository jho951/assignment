package io.github.jho951.assignment.job.worker;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import io.github.jho951.assignment.config.WorkerProperties;
import io.github.jho951.assignment.job.domain.JobFailureCode;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RestWorkerClient implements WorkerClient {

    private final RestClient restClient;
    private final WorkerProperties workerProperties;
    private final AtomicReference<String> cachedApiKey = new AtomicReference<>();

    @Override
    public WorkerStartResult startProcess(String imageUrl) {
        return withAuthorizedCall(apiKey -> {
            ProcessStartResponse response = restClient.post()
                    .uri(resolveWorkerUri(workerProperties.processPath()))
                    .header("X-API-KEY", apiKey)
                    .body(new ProcessRequest(imageUrl))
                    .retrieve()
                    .body(ProcessStartResponse.class);

            if (response == null || response.jobId() == null || response.status() == null) {
                throw new WorkerClientException(
                        JobFailureCode.INTERNAL_ERROR,
                        false,
                        "Mock Worker returned an invalid start response"
                );
            }

            return new WorkerStartResult(response.jobId(), WorkerRemoteStatus.valueOf(response.status()));
        });
    }

    @Override
    public WorkerStatusResult getProcessStatus(String workerJobId) {
        return withAuthorizedCall(apiKey -> {
            ProcessStatusResponse response = restClient.get()
                    .uri(resolveWorkerUri(workerProperties.processPath() + "/{jobId}", workerJobId))
                    .header("X-API-KEY", apiKey)
                    .retrieve()
                    .body(ProcessStatusResponse.class);

            if (response == null || response.jobId() == null || response.status() == null) {
                throw new WorkerClientException(
                        JobFailureCode.INTERNAL_ERROR,
                        false,
                        "Mock Worker returned an invalid status response"
                );
            }

            return new WorkerStatusResult(response.jobId(), WorkerRemoteStatus.valueOf(response.status()), response.result());
        });
    }

    private <T> T withAuthorizedCall(AuthorizedCall<T> authorizedCall) {
        String apiKey = getOrIssueApiKey();
        try {
            return authorizedCall.execute(apiKey);
        }
        catch (RestClientException exception) {
            WorkerClientException mappedException = mapException(exception);
            if (!(mappedException instanceof UnauthorizedWorkerException unauthorizedWorkerException)) {
                throw mappedException;
            }

            cachedApiKey.set(null);
            String refreshedApiKey;
            try {
                refreshedApiKey = issueApiKey();
            }
            catch (WorkerClientException issueKeyFailure) {
                throw new WorkerClientException(
                        JobFailureCode.WORKER_AUTH_FAILED,
                        issueKeyFailure.isRetryable(),
                        "Failed to refresh Mock Worker API key",
                        issueKeyFailure
                );
            }

            try {
                return authorizedCall.execute(refreshedApiKey);
            }
            catch (RestClientException retryException) {
                WorkerClientException retriedException = mapException(retryException);
                if (retriedException instanceof UnauthorizedWorkerException secondFailure) {
                    throw new WorkerClientException(
                            JobFailureCode.WORKER_AUTH_FAILED,
                            false,
                            "Mock Worker API key was rejected after refresh",
                            secondFailure
                    );
                }
                throw retriedException;
            }
            catch (UnauthorizedWorkerException secondFailure) {
                throw new WorkerClientException(
                        JobFailureCode.WORKER_AUTH_FAILED,
                        false,
                        "Mock Worker API key was rejected after refresh",
                        secondFailure
                );
            }
        }
    }

    private String getOrIssueApiKey() {
        String currentKey = cachedApiKey.get();
        if (currentKey != null && !currentKey.isBlank()) {
            return currentKey;
        }
        synchronized (cachedApiKey) {
            String cachedValue = cachedApiKey.get();
            if (cachedValue != null && !cachedValue.isBlank()) {
                return cachedValue;
            }
            return issueApiKey();
        }
    }

    private String issueApiKey() {
        try {
            IssueKeyResponse response = restClient.post()
                    .uri(resolveWorkerUri(workerProperties.issueKeyPath()))
                    .body(new IssueKeyRequest(workerProperties.candidateName(), workerProperties.candidateEmail()))
                    .retrieve()
                    .body(IssueKeyResponse.class);

            if (response == null || response.apiKey() == null || response.apiKey().isBlank()) {
                throw new WorkerClientException(
                        JobFailureCode.WORKER_AUTH_FAILED,
                        true,
                        "Mock Worker did not return a valid API key"
                );
            }

            cachedApiKey.set(response.apiKey());
            return response.apiKey();
        }
        catch (RestClientException exception) {
            throw mapException(exception);
        }
    }

    private WorkerClientException mapException(RestClientException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            HttpStatusCode statusCode = responseException.getStatusCode();
            int value = statusCode.value();
            if (value == 401) {
                return new UnauthorizedWorkerException("Mock Worker API key was rejected", responseException);
            }
            if (value == 429 || value >= 500) {
                return new WorkerClientException(
                        JobFailureCode.WORKER_UNAVAILABLE,
                        true,
                        "Mock Worker returned " + value,
                        responseException
                );
            }
            return new WorkerClientException(
                    JobFailureCode.WORKER_BAD_REQUEST,
                    false,
                    "Mock Worker returned " + value,
                    responseException
            );
        }

        if (exception instanceof ResourceAccessException resourceAccessException) {
            Throwable rootCause = resourceAccessException.getMostSpecificCause();
            if (rootCause instanceof SocketTimeoutException || containsTimeout(rootCause)) {
                return new WorkerClientException(
                        JobFailureCode.WORKER_TIMEOUT,
                        true,
                        "Mock Worker request timed out",
                        exception
                );
            }
            return new WorkerClientException(
                    JobFailureCode.WORKER_UNAVAILABLE,
                    true,
                    "Mock Worker is unavailable",
                    exception
            );
        }

        return new WorkerClientException(
                JobFailureCode.INTERNAL_ERROR,
                false,
                "Unexpected Worker client error",
                exception
        );
    }

    URI resolveWorkerUri(String pathTemplate, Object... uriVariables) {
        String normalizedBaseUrl = StringUtils.trimTrailingCharacter(workerProperties.baseUrl(), '/');
        String normalizedPath = pathTemplate.startsWith("/") ? pathTemplate : "/" + pathTemplate;
        return UriComponentsBuilder.fromUriString(normalizedBaseUrl)
                .path(normalizedPath)
                .buildAndExpand(uriVariables)
                .toUri();
    }

    private boolean containsTimeout(Throwable throwable) {
        return throwable != null
                && throwable.getMessage() != null
                && throwable.getMessage().toLowerCase().contains("timed out");
    }

    @FunctionalInterface
    private interface AuthorizedCall<T> {
        T execute(String apiKey);
    }

    private record IssueKeyRequest(
            String candidateName,
            String email
    ) {
    }

    private record IssueKeyResponse(
            String apiKey
    ) {
    }

    private record ProcessRequest(
            String imageUrl
    ) {
    }

    private record ProcessStartResponse(
            String jobId,
            String status
    ) {
    }

    private record ProcessStatusResponse(
            String jobId,
            String status,
            String result
    ) {
    }

    private static final class UnauthorizedWorkerException extends WorkerClientException {

        private UnauthorizedWorkerException(String message, Throwable cause) {
            super(JobFailureCode.WORKER_AUTH_FAILED, true, message, cause);
        }
    }
}

package io.github.jho951.assignment.brokerage;

import io.github.jho951.assignment.config.BrokerageProperties;
import io.github.jho951.assignment.order.domain.JobFailureCode;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Instant;
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

@Component
public class RestBrokerageClient implements BrokerageClient {

    private final RestClient restClient;
    private final BrokerageProperties brokerageProperties;
    private final AtomicReference<AccessTokenState> cachedToken = new AtomicReference<>();

    public RestBrokerageClient(RestClient restClient, BrokerageProperties brokerageProperties) {
        this.restClient = restClient;
        this.brokerageProperties = brokerageProperties;
    }

    @Override
    public BrokerageStartResult submitOrder(BrokerageOrderRequest request) {
        return withAuthorizedCall(accessToken -> {
            OrderResponse response = restClient.post()
                .uri(resolveBrokerageUri(brokerageProperties.orderPath()))
                .header("Authorization", "Bearer " + accessToken)
                .header("X-APP-KEY", brokerageProperties.appKey())
                .header("X-APP-SECRET", brokerageProperties.appSecret())
                .header("X-CLIENT-ID", brokerageProperties.clientId())
                .body(
                    new OrderSubmissionRequest(
                        request.brokerageCode(),
                        request.accountNumber(),
                        request.symbol(),
                        request.side().name(),
                        request.orderType().name(),
                        request.quantity(),
                        request.price()
                    )
                )
                .retrieve()
                .body(OrderResponse.class);

            return toStartResult(response, "submit");
        });
    }

    @Override
    public BrokerageStatusResult getOrderStatus(String brokerageOrderId) {
        return withAuthorizedCall(accessToken -> {
            OrderResponse response = restClient.get()
                .uri(resolveBrokerageUri(brokerageProperties.orderStatusPath(), brokerageOrderId))
                .header("Authorization", "Bearer " + accessToken)
                .header("X-APP-KEY", brokerageProperties.appKey())
                .header("X-APP-SECRET", brokerageProperties.appSecret())
                .header("X-CLIENT-ID", brokerageProperties.clientId())
                .retrieve()
                .body(OrderResponse.class);

            return toStatusResult(response, "status");
        });
    }

    public URI resolveBrokerageUri(String pathTemplate, Object... uriVariables) {
        String normalizedBaseUrl = StringUtils.trimTrailingCharacter(brokerageProperties.baseUrl(), '/');
        String normalizedPath = pathTemplate.startsWith("/") ? pathTemplate : "/" + pathTemplate;
        return UriComponentsBuilder.fromUriString(normalizedBaseUrl)
            .path(normalizedPath)
            .buildAndExpand(uriVariables)
            .toUri();
    }

    private <T> T withAuthorizedCall(AuthorizedCall<T> authorizedCall) {
        String accessToken = getOrIssueAccessToken();
        try {
            return authorizedCall.invoke(accessToken);
        } catch (RestClientException exception) {
            BrokerageClientException mappedException = mapException(exception);
            if (!(mappedException instanceof UnauthorizedBrokerageException)) {
                throw mappedException;
            }

            cachedToken.set(null);
            String refreshedAccessToken;
            try {
                refreshedAccessToken = issueAccessToken();
            } catch (BrokerageClientException issueTokenFailure) {
                throw new BrokerageClientException(
                    JobFailureCode.BROKERAGE_AUTH_FAILED,
                    issueTokenFailure.isRetryable(),
                    "Failed to refresh brokerage access token",
                    issueTokenFailure
                );
            }

            try {
                return authorizedCall.invoke(refreshedAccessToken);
            } catch (RestClientException retryException) {
                BrokerageClientException retriedException = mapException(retryException);
                if (retriedException instanceof UnauthorizedBrokerageException) {
                    throw new BrokerageClientException(
                        JobFailureCode.BROKERAGE_AUTH_FAILED,
                        false,
                        "Brokerage access token was rejected after refresh",
                        retriedException
                    );
                }
                throw retriedException;
            }
        }
    }

    private String getOrIssueAccessToken() {
        AccessTokenState currentToken = cachedToken.get();
        if (currentToken != null && currentToken.isUsable()) {
            return currentToken.accessToken();
        }

        synchronized (cachedToken) {
            AccessTokenState cachedValue = cachedToken.get();
            if (cachedValue != null && cachedValue.isUsable()) {
                return cachedValue.accessToken();
            }
            return issueAccessToken();
        }
    }

    private String issueAccessToken() {
        try {
            TokenResponse response = restClient.post()
                .uri(resolveBrokerageUri(brokerageProperties.tokenPath()))
                .body(new TokenRequest("client_credentials", brokerageProperties.appKey(), brokerageProperties.appSecret()))
                .retrieve()
                .body(TokenResponse.class);

            if (response == null || !StringUtils.hasText(response.accessToken())) {
                throw new BrokerageClientException(
                    JobFailureCode.BROKERAGE_AUTH_FAILED,
                    true,
                    "Brokerage did not return a valid access token"
                );
            }

            long expiresIn = response.expiresIn() == null ? 3600L : response.expiresIn();
            Instant expiresAt = Instant.now().plusSeconds(Math.max(expiresIn - 30L, 1L));
            cachedToken.set(new AccessTokenState(response.accessToken(), expiresAt));
            return response.accessToken();
        } catch (RestClientException exception) {
            throw mapException(exception);
        }
    }

    private BrokerageStartResult toStartResult(OrderResponse response, String operation) {
        validateResponse(response, operation);
        return new BrokerageStartResult(
            response.orderId(),
            parseStatus(response.status(), operation),
            normalizeQuantity(response.filledQuantity()),
            normalizeRemainingQuantity(response.remainingQuantity(), response.filledQuantity(), response.quantity()),
            response.averageExecutedPrice(),
            response.message()
        );
    }

    private BrokerageStatusResult toStatusResult(OrderResponse response, String operation) {
        validateResponse(response, operation);
        return new BrokerageStatusResult(
            response.orderId(),
            parseStatus(response.status(), operation),
            normalizeQuantity(response.filledQuantity()),
            normalizeRemainingQuantity(response.remainingQuantity(), response.filledQuantity(), response.quantity()),
            response.averageExecutedPrice(),
            response.message()
        );
    }

    private void validateResponse(OrderResponse response, String operation) {
        if (response == null || !StringUtils.hasText(response.orderId()) || !StringUtils.hasText(response.status())) {
            throw new BrokerageClientException(
                JobFailureCode.INTERNAL_ERROR,
                false,
                "Brokerage returned an invalid " + operation + " response"
            );
        }
    }

    private BrokerageRemoteStatus parseStatus(String status, String operation) {
        try {
            return BrokerageRemoteStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new BrokerageClientException(
                JobFailureCode.INTERNAL_ERROR,
                false,
                "Brokerage returned an unknown " + operation + " status: " + status,
                exception
            );
        }
    }

    private int normalizeQuantity(Integer quantity) {
        return quantity == null ? 0 : quantity;
    }

    private int normalizeRemainingQuantity(Integer remainingQuantity, Integer filledQuantity, Integer requestedQuantity) {
        if (remainingQuantity != null) {
            return remainingQuantity;
        }
        if (requestedQuantity != null && filledQuantity != null) {
            return Math.max(requestedQuantity - filledQuantity, 0);
        }
        return 0;
    }

    private BrokerageClientException mapException(RestClientException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            HttpStatusCode statusCode = responseException.getStatusCode();
            int value = statusCode.value();
            if (value == 401) {
                return new UnauthorizedBrokerageException("Brokerage access token was rejected", exception);
            }
            if (value == 429 || value >= 500) {
                return new BrokerageClientException(
                    JobFailureCode.BROKERAGE_UNAVAILABLE,
                    true,
                    "Brokerage returned " + value,
                    exception
                );
            }
            return new BrokerageClientException(
                JobFailureCode.BROKERAGE_BAD_REQUEST,
                false,
                "Brokerage returned " + value,
                exception
            );
        }

        if (exception instanceof ResourceAccessException resourceAccessException) {
            Throwable rootCause = resourceAccessException.getMostSpecificCause();
            if (rootCause instanceof SocketTimeoutException || containsTimeout(rootCause)) {
                return new BrokerageClientException(
                    JobFailureCode.BROKERAGE_TIMEOUT,
                    true,
                    "Brokerage request timed out",
                    exception
                );
            }
            return new BrokerageClientException(
                JobFailureCode.BROKERAGE_UNAVAILABLE,
                true,
                "Brokerage is unavailable",
                exception
            );
        }

        return new BrokerageClientException(
            JobFailureCode.INTERNAL_ERROR,
            false,
            "Unexpected brokerage client error",
            exception
        );
    }

    private boolean containsTimeout(Throwable throwable) {
        return throwable != null
            && throwable.getMessage() != null
            && throwable.getMessage().toLowerCase().contains("timed out");
    }

    private record AccessTokenState(String accessToken, Instant expiresAt) {
        private boolean isUsable() {
            return StringUtils.hasText(accessToken) && expiresAt != null && expiresAt.isAfter(Instant.now());
        }
    }

    private record TokenRequest(String grantType, String appKey, String appSecret) {
    }

    private record TokenResponse(String accessToken, Long expiresIn) {
    }

    private record OrderSubmissionRequest(
        String brokerageCode,
        String accountNumber,
        String symbol,
        String side,
        String orderType,
        Integer quantity,
        BigDecimal price
    ) {
    }

    private record OrderResponse(
        String orderId,
        String status,
        Integer quantity,
        Integer filledQuantity,
        Integer remainingQuantity,
        BigDecimal averageExecutedPrice,
        String message
    ) {
    }

    private interface AuthorizedCall<T> {
        T invoke(String accessToken);
    }

    private static final class UnauthorizedBrokerageException extends BrokerageClientException {
        private UnauthorizedBrokerageException(String message, Throwable cause) {
            super(JobFailureCode.BROKERAGE_AUTH_FAILED, true, message, cause);
        }
    }
}

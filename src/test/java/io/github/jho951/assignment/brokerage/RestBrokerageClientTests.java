package io.github.jho951.assignment.brokerage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.jho951.assignment.config.BrokerageProperties;
import io.github.jho951.assignment.order.domain.BrokerageOrderSide;
import io.github.jho951.assignment.order.domain.BrokerageOrderType;
import io.github.jho951.assignment.order.domain.JobFailureCode;
import java.math.BigDecimal;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestBrokerageClientTests {

    @Test
    void shouldResolveBrokerageUris() {
        RestBrokerageClient client = newClient(defaultProperties()).client();

        URI tokenUri = client.resolveBrokerageUri(defaultProperties().tokenPath());
        URI orderStatusUri = client.resolveBrokerageUri(defaultProperties().orderStatusPath(), "br-1");

        assertThat(tokenUri.toString()).isEqualTo("https://brokerage.example/oauth2/token");
        assertThat(orderStatusUri.toString()).isEqualTo("https://brokerage.example/v1/orders/br-1");
    }

    @Test
    void shouldIssueTokenAndSubmitOrder() {
        ClientFixture fixture = newClient(defaultProperties());
        fixture.server().expect(requestTo("https://brokerage.example/oauth2/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""
                {
                  "grantType": "client_credentials",
                  "appKey": "demo-app-key",
                  "appSecret": "demo-app-secret"
                }
                """))
            .andRespond(withSuccess("""
                {
                  "accessToken": "token-1",
                  "expiresIn": 3600
                }
                """, MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo("https://brokerage.example/v1/orders"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer token-1"))
            .andRespond(withSuccess("""
                {
                  "orderId": "br-1",
                  "status": "PENDING",
                  "quantity": 10,
                  "filledQuantity": 0,
                  "remainingQuantity": 10
                }
                """, MediaType.APPLICATION_JSON));

        BrokerageStartResult result = fixture.client().submitOrder(request());

        assertThat(result.brokerageOrderId()).isEqualTo("br-1");
        assertThat(result.status()).isEqualTo(BrokerageRemoteStatus.PENDING);
        fixture.server().verify();
    }

    @Test
    void shouldReuseCachedTokenForStatusCheck() {
        ClientFixture fixture = newClient(defaultProperties());
        fixture.server().expect(requestTo("https://brokerage.example/oauth2/token"))
            .andRespond(withSuccess(
                """
                {"accessToken":"token-1","expiresIn":3600}
                """,
                MediaType.APPLICATION_JSON
            ));
        fixture.server().expect(requestTo("https://brokerage.example/v1/orders"))
            .andExpect(header("Authorization", "Bearer token-1"))
            .andRespond(withSuccess(
                """
                {"orderId":"br-1","status":"PENDING","quantity":10,"remainingQuantity":10}
                """,
                MediaType.APPLICATION_JSON
            ));
        fixture.server().expect(requestTo("https://brokerage.example/v1/orders/br-1"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer token-1"))
            .andRespond(withSuccess(
                """
                {"orderId":"br-1","status":"FILLED","quantity":10,"filledQuantity":10,"remainingQuantity":0,"averageExecutedPrice":69950}
                """,
                MediaType.APPLICATION_JSON
            ));

        fixture.client().submitOrder(request());
        BrokerageStatusResult result = fixture.client().getOrderStatus("br-1");

        assertThat(result.status()).isEqualTo(BrokerageRemoteStatus.FILLED);
        assertThat(result.averageExecutedPrice()).isEqualByComparingTo("69950");
        fixture.server().verify();
    }

    @Test
    void shouldRefreshTokenAfterUnauthorized() {
        ClientFixture fixture = newClient(defaultProperties());
        fixture.server().expect(requestTo("https://brokerage.example/oauth2/token"))
            .andRespond(withSuccess(
                """
                {"accessToken":"token-old","expiresIn":3600}
                """,
                MediaType.APPLICATION_JSON
            ));
        fixture.server().expect(requestTo("https://brokerage.example/v1/orders"))
            .andExpect(header("Authorization", "Bearer token-old"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        fixture.server().expect(requestTo("https://brokerage.example/oauth2/token"))
            .andRespond(withSuccess(
                """
                {"accessToken":"token-new","expiresIn":3600}
                """,
                MediaType.APPLICATION_JSON
            ));
        fixture.server().expect(requestTo("https://brokerage.example/v1/orders"))
            .andExpect(header("Authorization", "Bearer token-new"))
            .andRespond(withSuccess(
                """
                {"orderId":"br-2","status":"FILLED","quantity":10,"filledQuantity":10,"remainingQuantity":0}
                """,
                MediaType.APPLICATION_JSON
            ));

        BrokerageStartResult result = fixture.client().submitOrder(request());

        assertThat(result.brokerageOrderId()).isEqualTo("br-2");
        assertThat(result.status()).isEqualTo(BrokerageRemoteStatus.FILLED);
    }

    @Test
    void shouldMapRepeatedUnauthorizedAsAuthFailure() {
        ClientFixture fixture = newClient(defaultProperties());
        fixture.server().expect(requestTo("https://brokerage.example/oauth2/token"))
            .andRespond(withSuccess(
                """
                {"accessToken":"token-old","expiresIn":3600}
                """,
                MediaType.APPLICATION_JSON
            ));
        fixture.server().expect(requestTo("https://brokerage.example/v1/orders/br-1"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        fixture.server().expect(requestTo("https://brokerage.example/oauth2/token"))
            .andRespond(withSuccess(
                """
                {"accessToken":"token-new","expiresIn":3600}
                """,
                MediaType.APPLICATION_JSON
            ));
        fixture.server().expect(requestTo("https://brokerage.example/v1/orders/br-1"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> fixture.client().getOrderStatus("br-1"))
            .isInstanceOf(BrokerageClientException.class)
            .satisfies(exception -> {
                BrokerageClientException clientException = (BrokerageClientException) exception;
                assertThat(clientException.getFailureCode()).isEqualTo(JobFailureCode.BROKERAGE_AUTH_FAILED);
                assertThat(clientException.isRetryable()).isFalse();
            });
    }

    private ClientFixture newClient(BrokerageProperties properties) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestBrokerageClient client = new RestBrokerageClient(builder.build(), properties);
        return new ClientFixture(client, server);
    }

    private BrokerageOrderRequest request() {
        return new BrokerageOrderRequest(
            "KIS",
            "12345678-01",
            "005930",
            BrokerageOrderSide.BUY,
            BrokerageOrderType.LIMIT,
            10,
            new BigDecimal("70000")
        );
    }

    private BrokerageProperties defaultProperties() {
        return new BrokerageProperties(
            "https://brokerage.example",
            "/oauth2/token",
            "/v1/orders",
            "/v1/orders/{orderId}",
            5_000,
            "demo-app-key",
            "demo-app-secret",
            "assignment-client"
        );
    }

    private record ClientFixture(RestBrokerageClient client, MockRestServiceServer server) {
    }
}

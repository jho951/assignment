package io.github.jho951.assignment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "brokerage")
public record BrokerageProperties(
    String baseUrl,
    String tokenPath,
    String orderPath,
    String orderStatusPath,
    long timeoutMs,
    String appKey,
    String appSecret,
    String clientId
) {
}

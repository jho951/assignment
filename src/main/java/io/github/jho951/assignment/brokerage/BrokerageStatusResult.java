package io.github.jho951.assignment.brokerage;

import java.math.BigDecimal;

public record BrokerageStatusResult(
    String brokerageOrderId,
    BrokerageRemoteStatus status,
    int filledQuantity,
    int remainingQuantity,
    BigDecimal averageExecutedPrice,
    String message
) {
}

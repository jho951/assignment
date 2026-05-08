package io.github.jho951.assignment.brokerage;

import java.math.BigDecimal;

public record BrokerageStartResult(
    String brokerageOrderId,
    BrokerageRemoteStatus status,
    int filledQuantity,
    int remainingQuantity,
    BigDecimal averageExecutedPrice,
    String message
) {
    public BrokerageStatusResult toStatusResult() {
        return new BrokerageStatusResult(
            brokerageOrderId,
            status,
            filledQuantity,
            remainingQuantity,
            averageExecutedPrice,
            message
        );
    }
}

package io.github.jho951.assignment.brokerage;

import io.github.jho951.assignment.order.domain.BrokerageOrderSide;
import io.github.jho951.assignment.order.domain.BrokerageOrderType;
import java.math.BigDecimal;

public record BrokerageOrderRequest(
    String brokerageCode,
    String accountNumber,
    String symbol,
    BrokerageOrderSide side,
    BrokerageOrderType orderType,
    int quantity,
    BigDecimal price
) {
}

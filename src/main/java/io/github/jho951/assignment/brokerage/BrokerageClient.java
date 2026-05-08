package io.github.jho951.assignment.brokerage;

public interface BrokerageClient {

    BrokerageStartResult submitOrder(BrokerageOrderRequest request);

    BrokerageStatusResult getOrderStatus(String brokerageOrderId);
}

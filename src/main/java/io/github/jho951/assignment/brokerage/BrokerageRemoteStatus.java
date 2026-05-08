package io.github.jho951.assignment.brokerage;

public enum BrokerageRemoteStatus {
    PENDING,
    PARTIALLY_FILLED,
    FILLED,
    REJECTED,
    CANCELLED;

    public boolean isTerminal() {
        return this == FILLED || this == REJECTED || this == CANCELLED;
    }
}

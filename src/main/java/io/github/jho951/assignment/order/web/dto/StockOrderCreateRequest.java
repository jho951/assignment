package io.github.jho951.assignment.order.web.dto;

import io.github.jho951.assignment.order.domain.BrokerageOrderSide;
import io.github.jho951.assignment.order.domain.BrokerageOrderType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record StockOrderCreateRequest(
    @NotBlank(message = "brokerageCode is required")
    @Pattern(regexp = "^[A-Z0-9_-]{2,16}$", message = "brokerageCode must be 2-16 characters of uppercase letters, digits, underscore, or hyphen")
    String brokerageCode,

    @NotBlank(message = "accountNumber is required")
    @Pattern(regexp = "^[0-9-]{8,32}$", message = "accountNumber must be 8-32 characters of digits or hyphen")
    String accountNumber,

    @NotBlank(message = "symbol is required")
    @Pattern(regexp = "^[A-Z0-9.]{1,16}$", message = "symbol must be 1-16 characters of uppercase letters, digits, or dot")
    String symbol,

    @NotNull(message = "side is required")
    BrokerageOrderSide side,

    @NotNull(message = "orderType is required")
    BrokerageOrderType orderType,

    @NotNull(message = "quantity is required")
    @Positive(message = "quantity must be positive")
    Integer quantity,

    @Digits(integer = 15, fraction = 4, message = "price must have up to 15 integer digits and 4 fraction digits")
    @DecimalMin(value = "0.0001", inclusive = true, message = "price must be positive")
    BigDecimal price
) {
    @AssertTrue(message = "price is required for LIMIT orders and optional for MARKET orders")
    public boolean isPriceValidForOrderType() {
        if (orderType == null) {
            return true;
        }
        if (orderType == BrokerageOrderType.LIMIT) {
            return price != null;
        }
        return true;
    }
}

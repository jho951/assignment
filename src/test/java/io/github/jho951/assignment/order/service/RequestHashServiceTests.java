package io.github.jho951.assignment.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jho951.assignment.order.domain.BrokerageOrderSide;
import io.github.jho951.assignment.order.domain.BrokerageOrderType;
import io.github.jho951.assignment.order.web.dto.StockOrderCreateRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RequestHashServiceTests {

    private final RequestHashService requestHashService = new RequestHashService();

    @Test
    void shouldProduceSameHashForSameRequest() {
        StockOrderCreateRequest request = new StockOrderCreateRequest(
            "KIS",
            "12345678-01",
            "005930",
            BrokerageOrderSide.BUY,
            BrokerageOrderType.LIMIT,
            10,
            new BigDecimal("70000")
        );

        String first = requestHashService.hashOrderRequest(request);
        String second = requestHashService.hashOrderRequest(request);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void shouldProduceDifferentHashWhenRequestChanges() {
        StockOrderCreateRequest first = new StockOrderCreateRequest(
            "KIS",
            "12345678-01",
            "005930",
            BrokerageOrderSide.BUY,
            BrokerageOrderType.LIMIT,
            10,
            new BigDecimal("70000")
        );
        StockOrderCreateRequest second = new StockOrderCreateRequest(
            "KIS",
            "12345678-01",
            "005930",
            BrokerageOrderSide.SELL,
            BrokerageOrderType.LIMIT,
            10,
            new BigDecimal("70000")
        );

        assertThat(requestHashService.hashOrderRequest(first))
            .isNotEqualTo(requestHashService.hashOrderRequest(second));
    }
}

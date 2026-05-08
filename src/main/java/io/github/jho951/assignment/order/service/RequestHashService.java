package io.github.jho951.assignment.order.service;

import io.github.jho951.assignment.order.web.dto.StockOrderCreateRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Service;

@Service
public class RequestHashService {

    public String hashOrderRequest(StockOrderCreateRequest request) {
        String canonical = String.join(
            "|",
            "brokerageCode=" + request.brokerageCode(),
            "accountNumber=" + request.accountNumber(),
            "symbol=" + request.symbol(),
            "side=" + request.side(),
            "orderType=" + request.orderType(),
            "quantity=" + request.quantity(),
            "price=" + request.price()
        );

        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digest = messageDigest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }
}

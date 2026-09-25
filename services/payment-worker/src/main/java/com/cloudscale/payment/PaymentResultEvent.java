package com.cloudscale.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResultEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String source,
        int version,
        Data data
) {

    public record Data(
            String orderId,
            String customerId,
            BigDecimal totalAmount,
            String currency,
            String paymentStatus,
            String failureReason
    ) {
    }

    public static PaymentResultEvent completed(
            String orderId,
            String customerId,
            BigDecimal totalAmount,
            String currency,
            Instant occurredAt
    ) {
        return new PaymentResultEvent(
                "evt-payment-completed-" + orderId,
                "PaymentCompleted",
                occurredAt,
                "cloudscale.payment-service",
                1,
                new Data(
                        orderId,
                        customerId,
                        totalAmount,
                        currency,
                        "COMPLETED",
                        null
                )
        );
    }

    public static PaymentResultEvent failed(
            String orderId,
            String customerId,
            BigDecimal totalAmount,
            String currency,
            String failureReason,
            Instant occurredAt
    ) {
        return new PaymentResultEvent(
                "evt-payment-failed-" + orderId,
                "PaymentFailed",
                occurredAt,
                "cloudscale.payment-service",
                1,
                new Data(
                        orderId,
                        customerId,
                        totalAmount,
                        currency,
                        "FAILED",
                        failureReason
                )
        );
    }
}
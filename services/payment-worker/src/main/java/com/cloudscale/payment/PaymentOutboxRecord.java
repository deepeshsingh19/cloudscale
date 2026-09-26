package com.cloudscale.payment;

import java.time.Instant;

public record PaymentOutboxRecord(
        String orderId,
        String eventId,
        String eventType,
        String eventPayload,
        String status,
        Instant createdAt
) {
    public static final String PENDING = "PENDING";
    public static final String PUBLISHED = "PUBLISHED";
}
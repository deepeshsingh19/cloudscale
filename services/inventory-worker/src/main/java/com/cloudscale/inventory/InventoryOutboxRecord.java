package com.cloudscale.inventory;

import java.time.Instant;

public record InventoryOutboxRecord(
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

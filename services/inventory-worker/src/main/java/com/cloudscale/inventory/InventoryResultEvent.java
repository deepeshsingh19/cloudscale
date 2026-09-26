package com.cloudscale.inventory;

import java.time.Instant;

public record InventoryResultEvent(
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
            String inventoryStatus,
            String failureReason
    ) {}
}

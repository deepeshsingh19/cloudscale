package com.cloudscale.inventory;

import java.time.Instant;
import java.util.Optional;

public interface InventoryRepository {

    boolean createInventoryAndOutboxIfPending(
            String orderId,
            String targetStatus,
            Instant updatedAt,
            InventoryOutboxRecord outboxRecord
    );

    Optional<InventoryOutboxRecord> findPendingOutbox(String orderId);

    boolean markOutboxPublished(
            String orderId,
            String eventId,
            Instant publishedAt
    );
}

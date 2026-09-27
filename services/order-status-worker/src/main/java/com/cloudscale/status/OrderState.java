package com.cloudscale.status;

import java.time.Instant;

public record OrderState(
        String orderId,
        String orderStatus,
        String paymentStatus,
        String inventoryStatus,
        Instant updatedAt
) {
}

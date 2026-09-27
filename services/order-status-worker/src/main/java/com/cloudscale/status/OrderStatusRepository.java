package com.cloudscale.status;

import java.time.Instant;
import java.util.Optional;

public interface OrderStatusRepository {

    Optional<OrderState> findById(String orderId);

    boolean updateState(
            OrderState expected,
            String orderStatus,
            String paymentStatus,
            String inventoryStatus,
            Instant updatedAt
    );
}

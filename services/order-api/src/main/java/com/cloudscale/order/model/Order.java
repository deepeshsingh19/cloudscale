package com.cloudscale.order.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Order(
        String orderId,
        String customerId,
        List<OrderItem> items,
        BigDecimal totalAmount,
        String currency,
        OrderStatus status,
        PaymentStatus paymentStatus,
        InventoryStatus inventoryStatus,
        Instant createdAt,
        Instant updatedAt
) {
}
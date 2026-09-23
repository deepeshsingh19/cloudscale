package com.cloudscale.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.cloudscale.order.model.Order;

public record OrderCreatedEvent(
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
            List<OrderItemData> items,
            BigDecimal totalAmount,
            String currency
    ) {
    }

    public record OrderItemData(
            String productId,
            int quantity,
            BigDecimal unitPrice
    ) {
    }

    public static OrderCreatedEvent from(
            Order order
    ) {
        List<OrderItemData> items =
                order.items()
                        .stream()
                        .map(item ->
                                new OrderItemData(
                                        item.productId(),
                                        item.quantity(),
                                        item.unitPrice()
                                )
                        )
                        .toList();

        return new OrderCreatedEvent(
                "evt-" + order.orderId(),
                "OrderCreated",
                order.createdAt(),
                "cloudscale.order-service",
                1,
                new Data(
                        order.orderId(),
                        order.customerId(),
                        items,
                        order.totalAmount(),
                        order.currency()
                )
        );
    }
}
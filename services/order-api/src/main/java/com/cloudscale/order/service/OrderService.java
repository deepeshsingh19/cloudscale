package com.cloudscale.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.cloudscale.order.dto.CreateOrderRequest;
import com.cloudscale.order.event.OrderEventPublisher;
import com.cloudscale.order.model.InventoryStatus;
import com.cloudscale.order.model.Order;
import com.cloudscale.order.model.OrderItem;
import com.cloudscale.order.model.OrderStatus;
import com.cloudscale.order.model.PaymentStatus;
import com.cloudscale.order.repository.OrderRepository;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final Optional<OrderEventPublisher> orderEventPublisher;

    public OrderService(
            OrderRepository orderRepository,
            Optional<OrderEventPublisher> orderEventPublisher
    ) {
        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher;
    }

    public Order createOrder(
            CreateOrderRequest request,
            String idempotencyKey
    ) {
        Instant now = Instant.now();

        List<OrderItem> items = request.items()
                .stream()
                .map(item -> new OrderItem(
                        item.productId(),
                        item.quantity(),
                        item.unitPrice()
                ))
                .toList();

        BigDecimal totalAmount = items.stream()
                .map(item ->
                        item.unitPrice().multiply(
                                BigDecimal.valueOf(item.quantity())
                        )
                )
                .reduce(
                        BigDecimal.ZERO,
                        BigDecimal::add
                );

        Order order = new Order(
                "ORD-" + UUID.randomUUID(),
                request.customerId(),
                items,
                totalAmount,
                request.currency(),
                OrderStatus.CREATED,
                PaymentStatus.PENDING,
                InventoryStatus.PENDING,
                now,
                now
        );

        long idempotencyExpiresAt = now
                .plusSeconds(24 * 60 * 60)
                .getEpochSecond();

        OrderRepository.CreateOrderResult result =
                orderRepository.createAtomically(
                        order,
                        idempotencyKey,
                        idempotencyExpiresAt
                );

        /*
         * Publish only when this request actually created
         * a new order. An idempotent retry must not publish
         * another OrderCreated event.
         */
        if (result.created()) {
            orderEventPublisher.ifPresent(
                    publisher ->
                            publisher.publishOrderCreated(
                                    result.order()
                            )
            );
        }

        return result.order();
    }

    public Order getOrder(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Order not found: " + orderId
                        )
                );
    }

    public List<Order> getOrders() {
        return orderRepository.findAll();
    }
}
package com.cloudscale.order.service;

import com.cloudscale.order.dto.CreateOrderRequest;
import com.cloudscale.order.model.InventoryStatus;
import com.cloudscale.order.model.Order;
import com.cloudscale.order.model.OrderItem;
import com.cloudscale.order.model.OrderStatus;
import com.cloudscale.order.model.PaymentStatus;
import com.cloudscale.order.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Order createOrder(
            CreateOrderRequest request,
            String idempotencyKey
    ) {
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
                        item.unitPrice()
                                .multiply(
                                        BigDecimal.valueOf(item.quantity())
                                )
                )
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Instant now = Instant.now();

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

        long idempotencyExpiresAt =
                now.plusSeconds(24 * 60 * 60).getEpochSecond();

        return orderRepository.createAtomically(
                order,
                idempotencyKey,
                idempotencyExpiresAt
        );
    }

    public Order getOrder(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Order not found: " + orderId
                        ));
    }

    public List<Order> getOrders() {
        return orderRepository.findAll();
    }
}
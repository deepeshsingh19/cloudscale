package com.cloudscale.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.cloudscale.order.dto.CreateOrderRequest;
import com.cloudscale.order.model.InventoryStatus;
import com.cloudscale.order.model.Order;
import com.cloudscale.order.model.OrderItem;
import com.cloudscale.order.model.OrderStatus;
import com.cloudscale.order.model.PaymentStatus;
import com.cloudscale.order.repository.IdempotencyRepository;
import com.cloudscale.order.repository.OrderRepository;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final IdempotencyRepository idempotencyRepository;

    public OrderService(
            OrderRepository orderRepository,
            IdempotencyRepository idempotencyRepository
    ) {
        this.orderRepository = orderRepository;
        this.idempotencyRepository = idempotencyRepository;
    }

    public Order createOrder(
            CreateOrderRequest request,
            String idempotencyKey
    ) {
        var existingOrderId =
                idempotencyRepository.findOrderId(idempotencyKey);

        if (existingOrderId.isPresent()) {
            return orderRepository
                    .findById(existingOrderId.get())
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "Idempotency record references a missing order"
                            ));
        }

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
                                .multiply(BigDecimal.valueOf(item.quantity()))
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

        Order saved = orderRepository.save(order);

        idempotencyRepository.save(
                idempotencyKey,
                saved.orderId()
        );

        return saved;
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
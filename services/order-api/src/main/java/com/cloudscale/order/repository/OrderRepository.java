package com.cloudscale.order.repository;

import java.util.List;
import java.util.Optional;

import com.cloudscale.order.model.Order;

public interface OrderRepository {

    CreateOrderResult createAtomically(
            Order order,
            String idempotencyKey,
            long idempotencyExpiresAt
    );

    Order save(Order order);

    Optional<Order> findById(String orderId);

    List<Order> findAll();

    record CreateOrderResult(
            Order order,
            boolean created
    ) {
    }
}
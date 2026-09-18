package com.cloudscale.order.repository;

import com.cloudscale.order.model.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@ConditionalOnProperty(
        name = "cloudscale.repository.type",
        havingValue = "in-memory",
        matchIfMissing = true
)
public class InMemoryOrderRepository implements OrderRepository {

    private final ConcurrentMap<String, Order> orders =
            new ConcurrentHashMap<>();

    private final ConcurrentMap<String, String> idempotencyKeys =
            new ConcurrentHashMap<>();

    @Override
    public synchronized Order createAtomically(
            Order order,
            String idempotencyKey,
            long idempotencyExpiresAt
    ) {
        String existingOrderId =
                idempotencyKeys.get(idempotencyKey);

        if (existingOrderId != null) {
            return orders.get(existingOrderId);
        }

        orders.put(order.orderId(), order);
        idempotencyKeys.put(
                idempotencyKey,
                order.orderId()
        );

        return order;
    }

    @Override
    public Order save(Order order) {
        orders.put(order.orderId(), order);
        return order;
    }

    @Override
    public Optional<Order> findById(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public List<Order> findAll() {
        return orders.values()
                .stream()
                .sorted(
                        Comparator.comparing(Order::createdAt)
                                .reversed()
                )
                .toList();
    }
}
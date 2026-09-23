package com.cloudscale.order.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import com.cloudscale.order.model.Order;

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
    public synchronized CreateOrderResult createAtomically(
            Order order,
            String idempotencyKey,
            long idempotencyExpiresAt
    ) {
        String existingOrderId =
                idempotencyKeys.get(idempotencyKey);

        if (existingOrderId != null) {
            Order existingOrder =
                    orders.get(existingOrderId);

            if (existingOrder == null) {
                throw new IllegalStateException(
                        "Idempotency record points to missing order: "
                                + existingOrderId
                );
            }

            return new CreateOrderResult(
                    existingOrder,
                    false
            );
        }

        orders.put(order.orderId(), order);

        idempotencyKeys.put(
                idempotencyKey,
                order.orderId()
        );

        return new CreateOrderResult(
                order,
                true
        );
    }

    @Override
    public Order save(Order order) {
        orders.put(order.orderId(), order);
        return order;
    }

    @Override
    public Optional<Order> findById(String orderId) {
        return Optional.ofNullable(
                orders.get(orderId)
        );
    }

    @Override
    public List<Order> findAll() {
        return new ArrayList<>(
                orders.values()
        );
    }
}
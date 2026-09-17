package com.cloudscale.order.repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Repository;

@Repository
public class InMemoryIdempotencyRepository implements IdempotencyRepository {

    private final ConcurrentMap<String, String> requests =
            new ConcurrentHashMap<>();

    @Override
    public Optional<String> findOrderId(String idempotencyKey) {
        return Optional.ofNullable(requests.get(idempotencyKey));
    }

    @Override
    public void save(String idempotencyKey, String orderId) {
        requests.putIfAbsent(idempotencyKey, orderId);
    }
}
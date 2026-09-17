package com.cloudscale.order.repository;

import java.util.Optional;

public interface IdempotencyRepository {

    Optional<String> findOrderId(String idempotencyKey);

    void save(String idempotencyKey, String orderId);
}
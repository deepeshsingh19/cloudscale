package com.cloudscale.payment;

import java.time.Instant;
import java.util.Optional;

public interface PaymentRepository {

    boolean createPaymentAndOutboxIfPending(
            String orderId,
            String targetStatus,
            Instant updatedAt,
            PaymentOutboxRecord outboxRecord
    );

    Optional<PaymentOutboxRecord> findPendingOutbox(
            String orderId
    );

    boolean markOutboxPublished(
            String orderId,
            String eventId,
            Instant publishedAt
    );
}
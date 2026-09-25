package com.cloudscale.payment;

import java.time.Instant;

public interface PaymentRepository {

    boolean markPaymentStatusIfPending(
            String orderId,
            String targetStatus,
            Instant updatedAt
    );
}
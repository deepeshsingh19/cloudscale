package com.cloudscale.payment;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PaymentProcessor {

    private static final String ORDER_CREATED =
            "OrderCreated";

    private final ObjectMapper objectMapper;
    private final PaymentRepository paymentRepository;
    private final PaymentEventPublisher paymentEventPublisher;
    private final String failureCustomerPrefix;

    public PaymentProcessor(
            ObjectMapper objectMapper,
            PaymentRepository paymentRepository,
            PaymentEventPublisher paymentEventPublisher,
            String failureCustomerPrefix
    ) {
        this.objectMapper = objectMapper;
        this.paymentRepository = paymentRepository;
        this.paymentEventPublisher = paymentEventPublisher;
        this.failureCustomerPrefix = failureCustomerPrefix;
    }

    public void processOrderCreatedEvent(
            String eventJson
    ) {
        JsonNode root;

        try {
            root = objectMapper.readTree(eventJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Invalid JSON event",
                    exception
            );
        }

        String detailType =
                requiredText(
                        root,
                        "detail-type"
                );

        if (!ORDER_CREATED.equals(detailType)) {
            throw new IllegalArgumentException(
                    "Unsupported event type: "
                            + detailType
            );
        }

        JsonNode data =
                root.path("detail")
                        .path("data");

        String orderId =
                requiredText(
                        data,
                        "orderId"
                );

        String customerId =
                requiredText(
                        data,
                        "customerId"
                );

        BigDecimal totalAmount;

        try {
            totalAmount =
                    new BigDecimal(
                            requiredText(
                                    data,
                                    "totalAmount"
                            )
                    );
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Invalid totalAmount",
                    exception
            );
        }

        String currency =
                requiredText(
                        data,
                        "currency"
                );

        Instant occurredAt =
                Instant.now();

        boolean paymentSucceeds =
                totalAmount.compareTo(
                        BigDecimal.ZERO
                ) > 0
                        && !customerId.startsWith(
                        failureCustomerPrefix
                );

        String targetStatus =
                paymentSucceeds
                        ? "COMPLETED"
                        : "FAILED";

        boolean transitioned =
                paymentRepository
                        .markPaymentStatusIfPending(
                                orderId,
                                targetStatus,
                                occurredAt
                        );

        /*
         * Another invocation has already processed this
         * order, so do not publish another result event.
         */
        if (!transitioned) {
            return;
        }

        PaymentResultEvent resultEvent;

        if (paymentSucceeds) {
            resultEvent =
                    PaymentResultEvent.completed(
                            orderId,
                            customerId,
                            totalAmount,
                            currency,
                            occurredAt
                    );
        } else {
            resultEvent =
                    PaymentResultEvent.failed(
                            orderId,
                            customerId,
                            totalAmount,
                            currency,
                            buildFailureReason(
                                    customerId,
                                    totalAmount
                            ),
                            occurredAt
                    );
        }

        paymentEventPublisher.publish(
                resultEvent
        );
    }

    private String buildFailureReason(
            String customerId,
            BigDecimal totalAmount
    ) {
        if (customerId.startsWith(
                failureCustomerPrefix
        )) {
            return "Payment declined by simulated payment processor";
        }

        if (totalAmount.compareTo(
                BigDecimal.ZERO
        ) <= 0) {
            return "Payment amount must be greater than zero";
        }

        return "Payment failed";
    }

    private String requiredText(
            JsonNode node,
            String field
    ) {
        JsonNode value =
                node.get(field);

        if (value == null
                || value.isNull()
                || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required field: "
                            + field
            );
        }

        return value.asText();
    }
}
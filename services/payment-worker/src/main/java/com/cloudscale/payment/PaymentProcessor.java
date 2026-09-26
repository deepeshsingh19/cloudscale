package com.cloudscale.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

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
                requiredText(data, "orderId");

        /*
         * First check whether a previous invocation already
         * created an outbox event but failed before publishing
         * or marking it published.
         */
        Optional<PaymentOutboxRecord> pendingOutbox =
                paymentRepository.findPendingOutbox(
                        orderId
                );

        if (pendingOutbox.isPresent()) {
            publishPendingOutbox(
                    pendingOutbox.get()
            );
            return;
        }

        String customerId =
                requiredText(
                        data,
                        "customerId"
                );

        BigDecimal totalAmount =
                parseAmount(
                        requiredText(
                                data,
                                "totalAmount"
                        )
                );

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

        PaymentResultEvent resultEvent =
                paymentSucceeds
                        ? PaymentResultEvent.completed(
                                orderId,
                                customerId,
                                totalAmount,
                                currency,
                                occurredAt
                        )
                        : PaymentResultEvent.failed(
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

        String eventPayload;

        try {
            eventPayload =
                    objectMapper.writeValueAsString(
                            resultEvent
                    );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize payment event",
                    exception
            );
        }

        PaymentOutboxRecord outboxRecord =
                new PaymentOutboxRecord(
                        orderId,
                        resultEvent.eventId(),
                        resultEvent.eventType(),
                        eventPayload,
                        PaymentOutboxRecord.PENDING,
                        occurredAt
                );

        boolean created =
                paymentRepository
                        .createPaymentAndOutboxIfPending(
                                orderId,
                                targetStatus,
                                occurredAt,
                                outboxRecord
                        );

        if (created) {
            publishAndMarkPublished(
                    resultEvent,
                    outboxRecord
            );
            return;
        }

        /*
         * Another Lambda invocation won the transaction race.
         * It may have created the outbox after our first lookup.
         */
        Optional<PaymentOutboxRecord> concurrentOutbox =
                paymentRepository.findPendingOutbox(
                        orderId
                );

        if (concurrentOutbox.isPresent()) {
            publishPendingOutbox(
                    concurrentOutbox.get()
            );
        }
    }

    private void publishPendingOutbox(
            PaymentOutboxRecord outbox
    ) {
        PaymentResultEvent event;

        try {
            event =
                    objectMapper.readValue(
                            outbox.eventPayload(),
                            PaymentResultEvent.class
                    );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to deserialize pending payment event",
                    exception
            );
        }

        publishAndMarkPublished(
                event,
                outbox
        );
    }

    private void publishAndMarkPublished(
            PaymentResultEvent event,
            PaymentOutboxRecord outbox
    ) {
        paymentEventPublisher.publish(event);

        paymentRepository.markOutboxPublished(
                outbox.orderId(),
                outbox.eventId(),
                Instant.now()
        );
    }

    private BigDecimal parseAmount(
            String value
    ) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Invalid totalAmount",
                    exception
            );
        }
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
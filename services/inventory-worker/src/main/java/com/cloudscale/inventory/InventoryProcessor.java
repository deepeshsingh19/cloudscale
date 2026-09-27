package com.cloudscale.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

public class InventoryProcessor {

    private static final String ORDER_CREATED = "OrderCreated";
    private static final String SOURCE = "cloudscale.inventory-service";
    private static final int VERSION = 1;
    private static final String RESERVED = "RESERVED";
    private static final String FAILED = "FAILED";

    private final InventoryRepository repository;
    private final InventoryEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final String failurePrefix;

    public InventoryProcessor(
            InventoryRepository repository,
            InventoryEventPublisher publisher,
            ObjectMapper objectMapper,
            String failurePrefix
    ) {
        this.repository = repository;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.failurePrefix = failurePrefix;
    }

    public void process(String messageBody) {
        JsonNode root;

        try {
            root = objectMapper.readTree(messageBody);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid event JSON", e);
        }

        JsonNode event = root;

        if (root.has("detail") && root.get("detail").isObject()) {
            event = root.get("detail");
        }

        String eventType = event.path("eventType").asText(null);

        if (eventType == null || eventType.isBlank()) {
            eventType = root.path("detail-type").asText(null);
        }

        if (!ORDER_CREATED.equals(eventType)) {
            return;
        }

        JsonNode data = event.path("data");

        String orderId = data.path("orderId").asText(null);
        String customerId = data.path("customerId").asText(null);

        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException(
                    "OrderCreated event is missing orderId"
            );
        }

        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException(
                    "OrderCreated event is missing customerId"
            );
        }

        Optional<InventoryOutboxRecord> existingOutbox =
                repository.findPendingOutbox(orderId);

        if (existingOutbox.isPresent()) {
            publishAndMark(existingOutbox.get());
            return;
        }

        boolean failed = customerId.startsWith(failurePrefix);

        String targetStatus = failed ? FAILED : RESERVED;

        String resultEventType = failed
                ? "InventoryFailed"
                : "InventoryReserved";

        String eventId = "evt-inventory-"
                + resultEventType.replace("Inventory", "").toLowerCase()
                + "-"
                + orderId;

        Instant occurredAt = Instant.now();

        InventoryResultEvent resultEvent =
                new InventoryResultEvent(
                        eventId,
                        resultEventType,
                        occurredAt,
                        SOURCE,
                        VERSION,
                        new InventoryResultEvent.Data(
                                orderId,
                                customerId,
                                targetStatus,
                                failed
                                        ? "Inventory unavailable for simulated inventory failure"
                                        : null
                        )
                );

        String payload;

        try {
            payload = objectMapper.writeValueAsString(resultEvent);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize inventory result event",
                    e
            );
        }

        InventoryOutboxRecord outboxRecord =
                new InventoryOutboxRecord(
                        orderId,
                        eventId,
                        resultEventType,
                        payload,
                        InventoryOutboxRecord.PENDING,
                        occurredAt
                );

        boolean created =
                repository.createInventoryAndOutboxIfPending(
                        orderId,
                        targetStatus,
                        occurredAt,
                        outboxRecord
                );

        if (created) {
            publishAndMark(outboxRecord);
            return;
        }

        repository.findPendingOutbox(orderId)
                .ifPresent(this::publishAndMark);
    }

    private void publishAndMark(InventoryOutboxRecord outboxRecord) {
        InventoryResultEvent event;

        try {
            event = objectMapper.readValue(
                    outboxRecord.eventPayload(),
                    InventoryResultEvent.class
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to deserialize inventory outbox event",
                    e
            );
        }

        publisher.publish(event);

        repository.markOutboxPublished(
                outboxRecord.orderId(),
                outboxRecord.eventId(),
                Instant.now()
        );
    }
}

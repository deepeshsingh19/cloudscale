package com.cloudscale.status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

public class OrderStatusProcessor {

    private static final int MAX_UPDATE_ATTEMPTS = 3;

    private final OrderStatusRepository repository;
    private final ObjectMapper objectMapper;

    public OrderStatusProcessor(
            OrderStatusRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void process(String messageBody) {
        JsonNode root;

        try {
            root = objectMapper.readTree(messageBody);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Invalid event JSON",
                    e
            );
        }

        JsonNode event = root;

        if (root.has("detail") && root.get("detail").isObject()) {
            event = root.get("detail");
        }

        String eventType = event.path("eventType").asText(null);

        if (eventType == null || eventType.isBlank()) {
            eventType = root.path("detail-type").asText(null);
        }

        if (!isSupported(eventType)) {
            return;
        }

        JsonNode data = event.path("data");

        String orderId = data.path("orderId").asText(null);

        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException(
                    "Worker result event is missing orderId"
            );
        }

        applyWithRetry(orderId, eventType);
    }

    private void applyWithRetry(
            String orderId,
            String eventType
    ) {
        for (int attempt = 1;
             attempt <= MAX_UPDATE_ATTEMPTS;
             attempt++) {

            Optional<OrderState> current =
                    repository.findById(orderId);

            if (current.isEmpty()) {
                throw new IllegalArgumentException(
                        "Order not found: " + orderId
                );
            }

            OrderState state = current.get();

            Transition transition =
                    transition(state, eventType);

            if (transition.sameAs(state)) {
                return;
            }

            Instant updatedAt = Instant.now();

            boolean updated =
                    repository.updateState(
                            state,
                            transition.orderStatus(),
                            transition.paymentStatus(),
                            transition.inventoryStatus(),
                            updatedAt
                    );

            if (updated) {
                return;
            }
        }

        throw new IllegalStateException(
                "Concurrent order update could not be applied after "
                        + MAX_UPDATE_ATTEMPTS
                        + " attempts: "
                        + orderId
        );
    }

    private Transition transition(
            OrderState state,
            String eventType
    ) {
        String paymentStatus = state.paymentStatus();
        String inventoryStatus = state.inventoryStatus();

        switch (eventType) {
            case "PaymentCompleted" ->
                    paymentStatus = "COMPLETED";

            case "PaymentFailed" ->
                    paymentStatus = "FAILED";

            case "InventoryReserved" ->
                    inventoryStatus = "RESERVED";

            case "InventoryFailed" ->
                    inventoryStatus = "FAILED";

            default -> {
                return new Transition(
                        state.orderStatus(),
                        paymentStatus,
                        inventoryStatus
                );
            }
        }

        String orderStatus =
                calculateOrderStatus(
                        state.orderStatus(),
                        paymentStatus,
                        inventoryStatus
                );

        return new Transition(
                orderStatus,
                paymentStatus,
                inventoryStatus
        );
    }

    private String calculateOrderStatus(
            String currentOrderStatus,
            String paymentStatus,
            String inventoryStatus
    ) {
        if ("CANCELLED".equals(currentOrderStatus)) {
            return "CANCELLED";
        }

        if ("FAILED".equals(paymentStatus)
                || "FAILED".equals(inventoryStatus)) {
            return "FAILED";
        }

        if ("COMPLETED".equals(paymentStatus)
                && "RESERVED".equals(inventoryStatus)) {
            return "FULFILLED";
        }

        return "PROCESSING";
    }

    private boolean isSupported(String eventType) {
        return "PaymentCompleted".equals(eventType)
                || "PaymentFailed".equals(eventType)
                || "InventoryReserved".equals(eventType)
                || "InventoryFailed".equals(eventType);
    }

    private record Transition(
            String orderStatus,
            String paymentStatus,
            String inventoryStatus
    ) {
        private boolean sameAs(OrderState state) {
            return orderStatus.equals(state.orderStatus())
                    && paymentStatus.equals(state.paymentStatus())
                    && inventoryStatus.equals(state.inventoryStatus());
        }
    }
}

package com.cloudscale.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderStatusProcessorTest {

    private OrderStatusRepository repository;
    private ObjectMapper objectMapper;
    private OrderStatusProcessor processor;

    @BeforeEach
    void setUp() {
        repository = mock(OrderStatusRepository.class);

        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();

        processor = new OrderStatusProcessor(
                repository,
                objectMapper
        );
    }

    @Test
    void shouldMoveToProcessingWhenPaymentCompletesFirst() {
        OrderState state = state(
                "ORD-001",
                "CREATED",
                "PENDING",
                "PENDING"
        );

        when(repository.findById("ORD-001"))
                .thenReturn(Optional.of(state));

        when(repository.updateState(
                eq(state),
                eq("PROCESSING"),
                eq("COMPLETED"),
                eq("PENDING"),
                any(Instant.class)
        )).thenReturn(true);

        processor.process(paymentEvent(
                "PaymentCompleted",
                "ORD-001"
        ));

        verify(repository).updateState(
                eq(state),
                eq("PROCESSING"),
                eq("COMPLETED"),
                eq("PENDING"),
                any(Instant.class)
        );
    }

    @Test
    void shouldMoveToFulfilledWhenInventoryCompletesSecond() {
        OrderState state = state(
                "ORD-002",
                "PROCESSING",
                "COMPLETED",
                "PENDING"
        );

        when(repository.findById("ORD-002"))
                .thenReturn(Optional.of(state));

        when(repository.updateState(
                eq(state),
                eq("FULFILLED"),
                eq("COMPLETED"),
                eq("RESERVED"),
                any(Instant.class)
        )).thenReturn(true);

        processor.process(inventoryEvent(
                "InventoryReserved",
                "ORD-002"
        ));

        verify(repository).updateState(
                eq(state),
                eq("FULFILLED"),
                eq("COMPLETED"),
                eq("RESERVED"),
                any(Instant.class)
        );
    }

    @Test
    void shouldMoveToFulfilledRegardlessOfEventOrder() {
        OrderState state = state(
                "ORD-003",
                "PROCESSING",
                "PENDING",
                "RESERVED"
        );

        when(repository.findById("ORD-003"))
                .thenReturn(Optional.of(state));

        when(repository.updateState(
                eq(state),
                eq("FULFILLED"),
                eq("COMPLETED"),
                eq("RESERVED"),
                any(Instant.class)
        )).thenReturn(true);

        processor.process(paymentEvent(
                "PaymentCompleted",
                "ORD-003"
        ));

        verify(repository).updateState(
                eq(state),
                eq("FULFILLED"),
                eq("COMPLETED"),
                eq("RESERVED"),
                any(Instant.class)
        );
    }

    @Test
    void shouldMoveToFailedWhenPaymentFails() {
        OrderState state = state(
                "ORD-004",
                "PROCESSING",
                "PENDING",
                "RESERVED"
        );

        when(repository.findById("ORD-004"))
                .thenReturn(Optional.of(state));

        when(repository.updateState(
                eq(state),
                eq("FAILED"),
                eq("FAILED"),
                eq("RESERVED"),
                any(Instant.class)
        )).thenReturn(true);

        processor.process(paymentEvent(
                "PaymentFailed",
                "ORD-004"
        ));

        verify(repository).updateState(
                eq(state),
                eq("FAILED"),
                eq("FAILED"),
                eq("RESERVED"),
                any(Instant.class)
        );
    }

    @Test
    void shouldMoveToFailedWhenInventoryFails() {
        OrderState state = state(
                "ORD-005",
                "PROCESSING",
                "COMPLETED",
                "PENDING"
        );

        when(repository.findById("ORD-005"))
                .thenReturn(Optional.of(state));

        when(repository.updateState(
                eq(state),
                eq("FAILED"),
                eq("COMPLETED"),
                eq("FAILED"),
                any(Instant.class)
        )).thenReturn(true);

        processor.process(inventoryEvent(
                "InventoryFailed",
                "ORD-005"
        ));

        verify(repository).updateState(
                eq(state),
                eq("FAILED"),
                eq("COMPLETED"),
                eq("FAILED"),
                any(Instant.class)
        );
    }

    @Test
    void shouldBeIdempotentForDuplicateResult() {
        OrderState state = state(
                "ORD-006",
                "FULFILLED",
                "COMPLETED",
                "RESERVED"
        );

        when(repository.findById("ORD-006"))
                .thenReturn(Optional.of(state));

        processor.process(paymentEvent(
                "PaymentCompleted",
                "ORD-006"
        ));

        verify(repository, never()).updateState(
                any(),
                anyString(),
                anyString(),
                anyString(),
                any()
        );
    }

    @Test
    void shouldProcessEventBridgeEnvelope() {
        OrderState state = state(
                "ORD-007",
                "CREATED",
                "PENDING",
                "PENDING"
        );

        when(repository.findById("ORD-007"))
                .thenReturn(Optional.of(state));

        when(repository.updateState(
                eq(state),
                eq("PROCESSING"),
                eq("COMPLETED"),
                eq("PENDING"),
                any(Instant.class)
        )).thenReturn(true);

        String detail = paymentEvent(
                "PaymentCompleted",
                "ORD-007"
        );

        processor.process("""
                {
                  "version": "0",
                  "id": "eventbridge-001",
                  "source": "cloudscale.payment-service",
                  "detail-type": "PaymentCompleted",
                  "detail": %s
                }
                """.formatted(detail));

        verify(repository).updateState(
                eq(state),
                eq("PROCESSING"),
                eq("COMPLETED"),
                eq("PENDING"),
                any(Instant.class)
        );
    }

    @Test
    void shouldIgnoreUnsupportedEvent() {
        processor.process("""
                {
                  "eventType": "OrderCreated",
                  "data": {
                    "orderId": "ORD-008"
                  }
                }
                """);

        verifyNoInteractions(repository);
    }

    @Test
    void shouldRejectInvalidJson() {
        assertThrows(
                IllegalArgumentException.class,
                () -> processor.process("{invalid-json")
        );
    }

    @Test
    void shouldRejectMissingOrderId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> processor.process("""
                        {
                          "eventType": "PaymentCompleted",
                          "data": {}
                        }
                        """)
        );
    }

    private OrderState state(
            String orderId,
            String orderStatus,
            String paymentStatus,
            String inventoryStatus
    ) {
        return new OrderState(
                orderId,
                orderStatus,
                paymentStatus,
                inventoryStatus,
                Instant.parse(
                        "2026-09-27T10:00:00Z"
                )
        );
    }

    private String paymentEvent(
            String eventType,
            String orderId
    ) {
        return """
                {
                  "eventId": "evt-test",
                  "eventType": "%s",
                  "occurredAt": "2026-09-27T10:00:00Z",
                  "source": "cloudscale.payment-service",
                  "version": 1,
                  "data": {
                    "orderId": "%s"
                  }
                }
                """.formatted(eventType, orderId);
    }

    private String inventoryEvent(
            String eventType,
            String orderId
    ) {
        return """
                {
                  "eventId": "evt-test",
                  "eventType": "%s",
                  "occurredAt": "2026-09-27T10:00:00Z",
                  "source": "cloudscale.inventory-service",
                  "version": 1,
                  "data": {
                    "orderId": "%s"
                  }
                }
                """.formatted(eventType, orderId);
    }
}

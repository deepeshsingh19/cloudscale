package com.cloudscale.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InventoryProcessorTest {

    private InventoryRepository repository;
    private InventoryEventPublisher publisher;
    private ObjectMapper objectMapper;
    private InventoryProcessor processor;

    @BeforeEach
    void setUp() {
        repository = mock(InventoryRepository.class);
        publisher = mock(InventoryEventPublisher.class);

        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();

        processor = new InventoryProcessor(
                repository,
                publisher,
                objectMapper,
                "FAIL-INVENTORY"
        );

        when(repository.findPendingOutbox(anyString()))
                .thenReturn(Optional.empty());
    }

    @Test
    void shouldReserveInventoryAndPublishResult() {
        when(repository.createInventoryAndOutboxIfPending(
                anyString(),
                anyString(),
                any(Instant.class),
                any(InventoryOutboxRecord.class)
        )).thenReturn(true);

        processor.process(orderCreatedEvent(
                "ORD-001",
                "CUS-001"
        ));

        verify(repository).createInventoryAndOutboxIfPending(
                eq("ORD-001"),
                eq("RESERVED"),
                any(Instant.class),
                any(InventoryOutboxRecord.class)
        );

        verify(publisher).publish(any(InventoryResultEvent.class));

        verify(repository).markOutboxPublished(
                eq("ORD-001"),
                eq("evt-inventory-reserved-ORD-001"),
                any(Instant.class)
        );
    }

    @Test
    void shouldProcessEventBridgeEnvelope() {
        when(repository.createInventoryAndOutboxIfPending(
                anyString(),
                anyString(),
                any(Instant.class),
                any(InventoryOutboxRecord.class)
        )).thenReturn(true);

        String detail = orderCreatedEvent(
                "ORD-ENVELOPE-001",
                "CUS-ENVELOPE-001"
        );

        String eventBridgeEnvelope = """
                {
                  "version": "0",
                  "id": "eventbridge-test-001",
                  "source": "cloudscale.order-service",
                  "detail-type": "OrderCreated",
                  "detail": %s
                }
                """.formatted(detail);

        processor.process(eventBridgeEnvelope);

        verify(repository).createInventoryAndOutboxIfPending(
                eq("ORD-ENVELOPE-001"),
                eq("RESERVED"),
                any(Instant.class),
                any(InventoryOutboxRecord.class)
        );

        verify(publisher).publish(any(InventoryResultEvent.class));

        verify(repository).markOutboxPublished(
                eq("ORD-ENVELOPE-001"),
                eq("evt-inventory-reserved-ORD-ENVELOPE-001"),
                any(Instant.class)
        );
    }

    @Test
    void shouldFailInventoryAndPublishResult() {
        when(repository.createInventoryAndOutboxIfPending(
                anyString(),
                anyString(),
                any(Instant.class),
                any(InventoryOutboxRecord.class)
        )).thenReturn(true);

        processor.process(orderCreatedEvent(
                "ORD-002",
                "FAIL-INVENTORY-001"
        ));

        verify(repository).createInventoryAndOutboxIfPending(
                eq("ORD-002"),
                eq("FAILED"),
                any(Instant.class),
                any(InventoryOutboxRecord.class)
        );

        verify(publisher).publish(any(InventoryResultEvent.class));

        verify(repository).markOutboxPublished(
                eq("ORD-002"),
                eq("evt-inventory-failed-ORD-002"),
                any(Instant.class)
        );
    }

    @Test
    void shouldRepublishExistingPendingOutbox() {
        InventoryResultEvent event =
                new InventoryResultEvent(
                        "evt-inventory-reserved-ORD-003",
                        "InventoryReserved",
                        Instant.now(),
                        "cloudscale.inventory-service",
                        1,
                        new InventoryResultEvent.Data(
                                "ORD-003",
                                "CUS-003",
                                "RESERVED",
                                null
                        )
                );

        String payload;

        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        InventoryOutboxRecord outbox =
                new InventoryOutboxRecord(
                        "ORD-003",
                        event.eventId(),
                        event.eventType(),
                        payload,
                        InventoryOutboxRecord.PENDING,
                        Instant.now()
                );

        when(repository.findPendingOutbox("ORD-003"))
                .thenReturn(Optional.of(outbox));

        processor.process(orderCreatedEvent(
                "ORD-003",
                "CUS-003"
        ));

        verify(publisher).publish(event);

        verify(repository).markOutboxPublished(
                eq("ORD-003"),
                eq("evt-inventory-reserved-ORD-003"),
                any(Instant.class)
        );

        verify(repository, never()).createInventoryAndOutboxIfPending(
                anyString(),
                anyString(),
                any(Instant.class),
                any(InventoryOutboxRecord.class)
        );
    }

    @Test
    void shouldDoNothingWhenAlreadyProcessed() {
        when(repository.createInventoryAndOutboxIfPending(
                anyString(),
                anyString(),
                any(Instant.class),
                any(InventoryOutboxRecord.class)
        )).thenReturn(false);

        processor.process(orderCreatedEvent(
                "ORD-004",
                "CUS-004"
        ));

        verify(publisher, never()).publish(any());
    }

    @Test
    void shouldIgnoreUnsupportedEvent() {
        processor.process("""
                {
                  "eventType": "PaymentCompleted",
                  "data": {
                    "orderId": "ORD-005"
                  }
                }
                """);

        verifyNoInteractions(repository);
        verifyNoInteractions(publisher);
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
                          "eventType": "OrderCreated",
                          "data": {
                            "customerId": "CUS-006"
                          }
                        }
                        """)
        );
    }

    private String orderCreatedEvent(
            String orderId,
            String customerId
    ) {
        return """
                {
                  "eventId": "evt-%s",
                  "eventType": "OrderCreated",
                  "occurredAt": "2026-09-26T13:00:00Z",
                  "source": "cloudscale.order-service",
                  "version": 1,
                  "data": {
                    "orderId": "%s",
                    "customerId": "%s",
                    "totalAmount": 749.50,
                    "currency": "INR"
                  }
                }
                """.formatted(orderId, orderId, customerId);
    }
}

package com.cloudscale.payment;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

class PaymentProcessorTest {

    private ObjectMapper objectMapper;
    private PaymentRepository paymentRepository;
    private PaymentEventPublisher paymentEventPublisher;
    private PaymentProcessor paymentProcessor;

    @BeforeEach
    void setUp() {
        objectMapper =
                JsonMapper.builder()
                        .addModule(
                                new JavaTimeModule()
                        )
                        .disable(
                                SerializationFeature
                                        .WRITE_DATES_AS_TIMESTAMPS
                        )
                        .build();

        paymentRepository =
                mock(PaymentRepository.class);

        paymentEventPublisher =
                mock(PaymentEventPublisher.class);

        paymentProcessor =
                new PaymentProcessor(
                        objectMapper,
                        paymentRepository,
                        paymentEventPublisher,
                        "FAIL-PAYMENT"
                );

        when(
                paymentRepository.findPendingOutbox(
                        anyString()
                )
        ).thenReturn(Optional.empty());
    }

    @Test
    void shouldCompleteSuccessfulPayment() {
        when(
                paymentRepository
                        .createPaymentAndOutboxIfPending(
                                anyString(),
                                anyString(),
                                any(Instant.class),
                                any(PaymentOutboxRecord.class)
                        )
        ).thenReturn(true);

        paymentProcessor.processOrderCreatedEvent(
                successOrderCreatedEvent()
        );

        verify(paymentRepository)
                .createPaymentAndOutboxIfPending(
                        eq("ORD-001"),
                        eq("COMPLETED"),
                        any(Instant.class),
                        any(PaymentOutboxRecord.class)
                );

        ArgumentCaptor<PaymentResultEvent> captor =
                ArgumentCaptor.forClass(
                        PaymentResultEvent.class
                );

        verify(paymentEventPublisher)
                .publish(captor.capture());

        assertEquals(
                "PaymentCompleted",
                captor.getValue().eventType()
        );

        verify(paymentRepository)
                .markOutboxPublished(
                        eq("ORD-001"),
                        eq("evt-payment-completed-ORD-001"),
                        any(Instant.class)
                );
    }

    @Test
    void shouldFailPaymentForConfiguredFailureCustomer() {
        when(
                paymentRepository
                        .createPaymentAndOutboxIfPending(
                                anyString(),
                                anyString(),
                                any(Instant.class),
                                any(PaymentOutboxRecord.class)
                        )
        ).thenReturn(true);

        paymentProcessor.processOrderCreatedEvent(
                failedOrderCreatedEvent()
        );

        verify(paymentRepository)
                .createPaymentAndOutboxIfPending(
                        eq("ORD-FAIL-001"),
                        eq("FAILED"),
                        any(Instant.class),
                        any(PaymentOutboxRecord.class)
                );

        ArgumentCaptor<PaymentResultEvent> captor =
                ArgumentCaptor.forClass(
                        PaymentResultEvent.class
                );

        verify(paymentEventPublisher)
                .publish(captor.capture());

        assertEquals(
                "PaymentFailed",
                captor.getValue().eventType()
        );
    }

    @Test
    void shouldRepublishExistingPendingOutbox() {
        Instant createdAt =
                Instant.parse(
                        "2026-09-25T10:00:00Z"
                );

        PaymentResultEvent event =
                PaymentResultEvent.completed(
                        "ORD-001",
                        "CUS-001",
                        new java.math.BigDecimal("1000.00"),
                        "INR",
                        createdAt
                );

        String payload;

        try {
            payload =
                    objectMapper.writeValueAsString(
                            event
                    );
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }

        PaymentOutboxRecord outbox =
                new PaymentOutboxRecord(
                        "ORD-001",
                        event.eventId(),
                        event.eventType(),
                        payload,
                        PaymentOutboxRecord.PENDING,
                        createdAt
                );

        when(
                paymentRepository.findPendingOutbox(
                        "ORD-001"
                )
        ).thenReturn(
                Optional.of(outbox)
        );

        paymentProcessor.processOrderCreatedEvent(
                successOrderCreatedEvent()
        );

        verify(paymentRepository, never())
                .createPaymentAndOutboxIfPending(
                        anyString(),
                        anyString(),
                        any(Instant.class),
                        any(PaymentOutboxRecord.class)
                );

        verify(paymentEventPublisher)
                .publish(any(PaymentResultEvent.class));

        verify(paymentRepository)
                .markOutboxPublished(
                        eq("ORD-001"),
                        eq(event.eventId()),
                        any(Instant.class)
                );
    }

    @Test
    void shouldIgnoreAlreadyProcessedOrderWithoutPendingOutbox() {
        when(
                paymentRepository
                        .createPaymentAndOutboxIfPending(
                                anyString(),
                                anyString(),
                                any(Instant.class),
                                any(PaymentOutboxRecord.class)
                        )
        ).thenReturn(false);

        paymentProcessor.processOrderCreatedEvent(
                successOrderCreatedEvent()
        );

        verify(paymentEventPublisher, never())
                .publish(
                        any(PaymentResultEvent.class)
                );
    }

    @Test
    void shouldRejectUnsupportedEventType() {
        String event =
                """
                {
                  "detail-type": "PaymentCompleted",
                  "detail": {}
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        paymentProcessor
                                .processOrderCreatedEvent(
                                        event
                                )
        );
    }

    @Test
    void shouldRejectInvalidJson() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        paymentProcessor
                                .processOrderCreatedEvent(
                                        "{invalid-json"
                                )
        );
    }

    @Test
    void shouldRejectMissingOrderId() {
        String event =
                """
                {
                  "detail-type": "OrderCreated",
                  "detail": {
                    "data": {
                      "customerId": "CUS-001",
                      "totalAmount": "1000.00",
                      "currency": "INR"
                    }
                  }
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        paymentProcessor
                                .processOrderCreatedEvent(
                                        event
                                )
        );
    }

    private String successOrderCreatedEvent() {
        return """
                {
                  "version": "0",
                  "id": "eventbridge-id-001",
                  "detail-type": "OrderCreated",
                  "source": "cloudscale.order-service",
                  "detail": {
                    "eventId": "evt-ORD-001",
                    "eventType": "OrderCreated",
                    "occurredAt": "2026-09-25T10:00:00Z",
                    "source": "cloudscale.order-service",
                    "version": 1,
                    "data": {
                      "orderId": "ORD-001",
                      "customerId": "CUS-001",
                      "totalAmount": "1000.00",
                      "currency": "INR"
                    }
                  }
                }
                """;
    }

    private String failedOrderCreatedEvent() {
        return """
                {
                  "version": "0",
                  "id": "eventbridge-id-002",
                  "detail-type": "OrderCreated",
                  "source": "cloudscale.order-service",
                  "detail": {
                    "eventId": "evt-ORD-FAIL-001",
                    "eventType": "OrderCreated",
                    "occurredAt": "2026-09-25T10:00:00Z",
                    "source": "cloudscale.order-service",
                    "version": 1,
                    "data": {
                      "orderId": "ORD-FAIL-001",
                      "customerId": "FAIL-PAYMENT-001",
                      "totalAmount": "1000.00",
                      "currency": "INR"
                    }
                  }
                }
                """;
    }
}
package com.cloudscale.payment;

import java.time.Instant;

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
        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
                )
                .build();

        paymentRepository = mock(
                PaymentRepository.class
        );

        paymentEventPublisher = mock(
                PaymentEventPublisher.class
        );

        paymentProcessor = new PaymentProcessor(
                objectMapper,
                paymentRepository,
                paymentEventPublisher,
                "FAIL-PAYMENT"
        );
    }

    @Test
    void shouldCompleteSuccessfulPayment() {
        when(
                paymentRepository.markPaymentStatusIfPending(
                        anyString(),
                        anyString(),
                        any(Instant.class)
                )
        ).thenReturn(true);

        paymentProcessor.processOrderCreatedEvent(
                successOrderCreatedEvent()
        );

        verify(paymentRepository)
                .markPaymentStatusIfPending(
                        eq("ORD-001"),
                        eq("COMPLETED"),
                        any(Instant.class)
                );

        ArgumentCaptor<PaymentResultEvent> captor =
                ArgumentCaptor.forClass(
                        PaymentResultEvent.class
                );

        verify(paymentEventPublisher)
                .publish(captor.capture());

        PaymentResultEvent event =
                captor.getValue();

        assertEquals(
                "PaymentCompleted",
                event.eventType()
        );

        assertEquals(
                "COMPLETED",
                event.data().paymentStatus()
        );

        assertEquals(
                "ORD-001",
                event.data().orderId()
        );

        assertEquals(
                "CUS-001",
                event.data().customerId()
        );
    }

    @Test
    void shouldFailPaymentForConfiguredFailureCustomer() {
        when(
                paymentRepository.markPaymentStatusIfPending(
                        anyString(),
                        anyString(),
                        any(Instant.class)
                )
        ).thenReturn(true);

        paymentProcessor.processOrderCreatedEvent(
                failedOrderCreatedEvent()
        );

        verify(paymentRepository)
                .markPaymentStatusIfPending(
                        eq("ORD-FAIL-001"),
                        eq("FAILED"),
                        any(Instant.class)
                );

        ArgumentCaptor<PaymentResultEvent> captor =
                ArgumentCaptor.forClass(
                        PaymentResultEvent.class
                );

        verify(paymentEventPublisher)
                .publish(captor.capture());

        PaymentResultEvent event =
                captor.getValue();

        assertEquals(
                "PaymentFailed",
                event.eventType()
        );

        assertEquals(
                "FAILED",
                event.data().paymentStatus()
        );

        assertEquals(
                "ORD-FAIL-001",
                event.data().orderId()
        );
    }

    @Test
    void shouldIgnoreDuplicateOrderCreatedEvent() {
        when(
                paymentRepository.markPaymentStatusIfPending(
                        anyString(),
                        anyString(),
                        any(Instant.class)
                )
        ).thenReturn(false);

        paymentProcessor.processOrderCreatedEvent(
                successOrderCreatedEvent()
        );

        verify(paymentRepository)
                .markPaymentStatusIfPending(
                        eq("ORD-001"),
                        eq("COMPLETED"),
                        any(Instant.class)
                );

        verify(
                paymentEventPublisher,
                never()
        ).publish(any(PaymentResultEvent.class));
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

        verify(
                paymentRepository,
                never()
        ).markPaymentStatusIfPending(
                anyString(),
                anyString(),
                any(Instant.class)
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

        verify(
                paymentRepository,
                never()
        ).markPaymentStatusIfPending(
                anyString(),
                anyString(),
                any(Instant.class)
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

        verify(
                paymentRepository,
                never()
        ).markPaymentStatusIfPending(
                anyString(),
                anyString(),
                any(Instant.class)
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
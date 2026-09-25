package com.cloudscale.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

public class EventBridgePaymentEventPublisher
        implements PaymentEventPublisher {

    private final EventBridgeClient eventBridgeClient;
    private final ObjectMapper objectMapper;
    private final String eventBusName;

    public EventBridgePaymentEventPublisher(
            EventBridgeClient eventBridgeClient,
            ObjectMapper objectMapper,
            String eventBusName
    ) {
        this.eventBridgeClient = eventBridgeClient;
        this.objectMapper = objectMapper;
        this.eventBusName = eventBusName;
    }

    @Override
    public void publish(
            PaymentResultEvent event
    ) {
        try {
            String detail =
                    objectMapper.writeValueAsString(
                            event
                    );

            PutEventsRequestEntry entry =
                    PutEventsRequestEntry.builder()
                            .eventBusName(eventBusName)
                            .source(
                                    event.source()
                            )
                            .detailType(
                                    event.eventType()
                            )
                            .detail(detail)
                            .build();

            PutEventsResponse response =
                    eventBridgeClient.putEvents(
                            PutEventsRequest.builder()
                                    .entries(entry)
                                    .build()
                    );

            if (response.failedEntryCount() != null
                    && response.failedEntryCount() > 0) {
                throw new IllegalStateException(
                        "Failed to publish payment event: "
                                + response.entries()
                );
            }

        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize payment event",
                    exception
            );
        }
    }
}
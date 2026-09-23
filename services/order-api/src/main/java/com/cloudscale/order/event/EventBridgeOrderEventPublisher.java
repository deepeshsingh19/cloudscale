package com.cloudscale.order.event;

import com.cloudscale.order.model.Order;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

public class EventBridgeOrderEventPublisher
        implements OrderEventPublisher {

    private final EventBridgeClient eventBridgeClient;
    private final ObjectMapper objectMapper;
    private final String eventBusName;
    private final String source;

    public EventBridgeOrderEventPublisher(
            EventBridgeClient eventBridgeClient,
            ObjectMapper objectMapper,
            String eventBusName,
            String source
    ) {
        this.eventBridgeClient = eventBridgeClient;
        this.objectMapper = objectMapper;
        this.eventBusName = eventBusName;
        this.source = source;
    }

    @Override
    public void publishOrderCreated(
            Order order
    ) {
        OrderCreatedEvent event =
                OrderCreatedEvent.from(order);

        try {
            String detail =
                    objectMapper.writeValueAsString(
                            event
                    );

            PutEventsRequestEntry entry =
                    PutEventsRequestEntry.builder()
                            .eventBusName(eventBusName)
                            .source(source)
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
                        "Failed to publish OrderCreated event: "
                                + response.entries()
                );
            }

        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize OrderCreated event",
                    exception
            );
        }
    }
}
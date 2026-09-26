package com.cloudscale.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

public class EventBridgeInventoryEventPublisher implements InventoryEventPublisher {

    private final EventBridgeClient eventBridgeClient;
    private final ObjectMapper objectMapper;
    private final String eventBusName;
    private final String source;

    public EventBridgeInventoryEventPublisher(
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
    public void publish(InventoryResultEvent event) {
        final String detail;

        try {
            detail = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize inventory event",
                    e
            );
        }

        PutEventsResponse response = eventBridgeClient.putEvents(
                PutEventsRequest.builder()
                        .entries(
                                PutEventsRequestEntry.builder()
                                        .eventBusName(eventBusName)
                                        .source(source)
                                        .detailType(event.eventType())
                                        .detail(detail)
                                        .build()
                        )
                        .build()
        );

        if (response.failedEntryCount() != null
                && response.failedEntryCount() > 0) {
            throw new IllegalStateException(
                    "EventBridge failed to publish inventory event: "
                            + response.entries()
            );
        }
    }
}

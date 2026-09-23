package com.cloudscale.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.cloudscale.order.event.EventBridgeOrderEventPublisher;
import com.cloudscale.order.event.OrderEventPublisher;

import software.amazon.awssdk.services.eventbridge.EventBridgeClient;

@Configuration
public class EventBridgeConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "cloudscale.event-publishing.enabled",
            havingValue = "true"
    )
    public EventBridgeClient eventBridgeClient() {
        return EventBridgeClient.create();
    }

    @Bean
    @ConditionalOnProperty(
            name = "cloudscale.event-publishing.enabled",
            havingValue = "true"
    )
    public OrderEventPublisher orderEventPublisher(
            EventBridgeClient eventBridgeClient,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            @Value("${cloudscale.eventbridge.bus-name}")
            String eventBusName,
            @Value("${cloudscale.eventbridge.source}")
            String source
    ) {
        return new EventBridgeOrderEventPublisher(
                eventBridgeClient,
                objectMapper,
                eventBusName,
                source
        );
    }
}
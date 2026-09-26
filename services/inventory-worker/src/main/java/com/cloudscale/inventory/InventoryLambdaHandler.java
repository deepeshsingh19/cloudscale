package com.cloudscale.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.fasterxml.jackson.databind.SerializationFeature;

public class InventoryLambdaHandler implements RequestStreamHandler {

    private static final String DEFAULT_TABLE = "cloudscale-dev-orders";
    private static final String DEFAULT_BUS = "cloudscale-dev-events";
    private static final String DEFAULT_FAILURE_PREFIX = "FAIL-INVENTORY";

    private final ObjectMapper objectMapper;
    private final InventoryProcessor processor;

    public InventoryLambdaHandler() {
        this.objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        String tableName = System.getenv()
                .getOrDefault("CLOUDSCALE_TABLE_NAME", DEFAULT_TABLE);

        String eventBusName = System.getenv()
                .getOrDefault("CLOUDSCALE_EVENT_BUS", DEFAULT_BUS);

        String failurePrefix = System.getenv()
                .getOrDefault(
                        "CLOUDSCALE_INVENTORY_FAILURE_PREFIX",
                        DEFAULT_FAILURE_PREFIX
                );

        DynamoDbClient dynamoDbClient =
                DynamoDbClient.builder().build();

        EventBridgeClient eventBridgeClient =
                EventBridgeClient.builder().build();

        InventoryRepository repository =
                new DynamoDbInventoryRepository(
                        dynamoDbClient,
                        tableName
                );

        InventoryEventPublisher publisher =
                new EventBridgeInventoryEventPublisher(
                        eventBridgeClient,
                        objectMapper,
                        eventBusName,
                        "cloudscale.inventory-service"
                );

        this.processor =
                new InventoryProcessor(
                        repository,
                        publisher,
                        objectMapper,
                        failurePrefix
                );
    }

    @Override
    public void handleRequest(
            InputStream input,
            OutputStream output,
            Context context
    ) throws IOException {

        JsonNode root = objectMapper.readTree(input);

        JsonNode records = root.path("Records");

        if (!records.isArray()) {
            throw new IllegalArgumentException(
                    "SQS Lambda event does not contain Records"
            );
        }

        for (JsonNode record : records) {
            String body = record.path("body").asText(null);

            if (body == null || body.isBlank()) {
                throw new IllegalArgumentException(
                        "SQS record is missing body"
                );
            }

            processor.process(body);
        }
    }
}

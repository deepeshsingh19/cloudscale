package com.cloudscale.status;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class OrderStatusLambdaHandler
        implements RequestStreamHandler {

    private static final String DEFAULT_TABLE =
            "cloudscale-dev-orders";

    private final ObjectMapper objectMapper;
    private final OrderStatusProcessor processor;

    public OrderStatusLambdaHandler() {
        this.objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
                )
                .build();

        String tableName = System.getenv()
                .getOrDefault(
                        "CLOUDSCALE_TABLE_NAME",
                        DEFAULT_TABLE
                );

        DynamoDbClient dynamoDbClient =
                DynamoDbClient.builder().build();

        OrderStatusRepository repository =
                new DynamoDbOrderStatusRepository(
                        dynamoDbClient,
                        tableName
                );

        this.processor =
                new OrderStatusProcessor(
                        repository,
                        objectMapper
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

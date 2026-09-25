package com.cloudscale.payment;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class PaymentLambdaHandler
        implements RequestStreamHandler {

    private static final ObjectMapper OBJECT_MAPPER =
            JsonMapper.builder()
                    .addModule(
                            new JavaTimeModule()
                    )
                    .disable(
                            SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS
                    )
                    .build();

    private static final DynamoDbClient DYNAMO_DB_CLIENT =
            DynamoDbClient.create();

    private static final EventBridgeClient EVENT_BRIDGE_CLIENT =
            EventBridgeClient.create();

    private final PaymentProcessor paymentProcessor;

    public PaymentLambdaHandler() {
        String tableName =
                environment(
                        "CLOUDSCALE_TABLE_NAME",
                        "cloudscale-dev-orders"
                );

        String eventBusName =
                environment(
                        "CLOUDSCALE_EVENT_BUS",
                        "cloudscale-dev-events"
                );

        String failureCustomerPrefix =
                environment(
                        "CLOUDSCALE_PAYMENT_FAILURE_PREFIX",
                        "FAIL-PAYMENT"
                );

        PaymentRepository repository =
                new DynamoDbPaymentRepository(
                        DYNAMO_DB_CLIENT,
                        tableName
                );

        PaymentEventPublisher publisher =
                new EventBridgePaymentEventPublisher(
                        EVENT_BRIDGE_CLIENT,
                        OBJECT_MAPPER,
                        eventBusName
                );

        this.paymentProcessor =
                new PaymentProcessor(
                        OBJECT_MAPPER,
                        repository,
                        publisher,
                        failureCustomerPrefix
                );
    }

    PaymentLambdaHandler(
            PaymentProcessor paymentProcessor
    ) {
        this.paymentProcessor =
                paymentProcessor;
    }

    @Override
    public void handleRequest(
            InputStream input,
            OutputStream output,
            Context context
    ) throws IOException {

        var root =
                OBJECT_MAPPER.readTree(input);

        var records =
                root.path("Records");

        if (!records.isArray()) {
            throw new IllegalArgumentException(
                    "Expected SQS event with Records array"
            );
        }

        for (var record : records) {
            String body =
                    record.path("body")
                            .asText(null);

            if (body == null
                    || body.isBlank()) {
                throw new IllegalArgumentException(
                        "SQS record is missing body"
                );
            }

            paymentProcessor
                    .processOrderCreatedEvent(
                            body
                    );
        }

        output.write(
                "{\"status\":\"ok\"}"
                        .getBytes(
                                StandardCharsets.UTF_8
                        )
        );
    }

    private static String environment(
            String name,
            String defaultValue
    ) {
        String value =
                System.getenv(name);

        return value == null
                || value.isBlank()
                ? defaultValue
                : value;
    }
}
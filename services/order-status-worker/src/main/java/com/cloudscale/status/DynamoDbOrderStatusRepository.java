package com.cloudscale.status;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class DynamoDbOrderStatusRepository
        implements OrderStatusRepository {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DynamoDbOrderStatusRepository(
            DynamoDbClient dynamoDbClient,
            String tableName
    ) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public Optional<OrderState> findById(String orderId) {
        Map<String, AttributeValue> key = Map.of(
                "PK",
                AttributeValue.builder()
                        .s("ORDER#" + orderId)
                        .build(),
                "SK",
                AttributeValue.builder()
                        .s("ORDER")
                        .build()
        );

        var response = dynamoDbClient.getItem(
                GetItemRequest.builder()
                        .tableName(tableName)
                        .key(key)
                        .consistentRead(true)
                        .projectionExpression(
                                "orderId, #status, paymentStatus, inventoryStatus, updatedAt"
                        )
                        .expressionAttributeNames(
                                Map.of("#status", "status")
                        )
                        .build()
        );

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }

        Map<String, AttributeValue> item = response.item();

        return Optional.of(
                new OrderState(
                        required(item, "orderId"),
                        required(item, "status"),
                        required(item, "paymentStatus"),
                        required(item, "inventoryStatus"),
                        Instant.parse(required(item, "updatedAt"))
                )
        );
    }

    @Override
    public boolean updateState(
            OrderState expected,
            String orderStatus,
            String paymentStatus,
            String inventoryStatus,
            Instant updatedAt
    ) {
        Map<String, AttributeValue> key = Map.of(
                "PK",
                AttributeValue.builder()
                        .s("ORDER#" + expected.orderId())
                        .build(),
                "SK",
                AttributeValue.builder()
                        .s("ORDER")
                        .build()
        );

        Map<String, AttributeValue> values = new HashMap<>();

        values.put(
                ":orderStatus",
                AttributeValue.builder()
                        .s(orderStatus)
                        .build()
        );

        values.put(
                ":paymentStatus",
                AttributeValue.builder()
                        .s(paymentStatus)
                        .build()
        );

        values.put(
                ":inventoryStatus",
                AttributeValue.builder()
                        .s(inventoryStatus)
                        .build()
        );

        values.put(
                ":updatedAt",
                AttributeValue.builder()
                        .s(updatedAt.toString())
                        .build()
        );

        values.put(
                ":expectedUpdatedAt",
                AttributeValue.builder()
                        .s(expected.updatedAt().toString())
                        .build()
        );

        try {
            dynamoDbClient.updateItem(
                    UpdateItemRequest.builder()
                            .tableName(tableName)
                            .key(key)
                            .updateExpression(
                                    "SET #status = :orderStatus, " +
                                    "paymentStatus = :paymentStatus, " +
                                    "inventoryStatus = :inventoryStatus, " +
                                    "updatedAt = :updatedAt"
                            )
                            .conditionExpression(
                                    "attribute_exists(PK) AND " +
                                    "updatedAt = :expectedUpdatedAt"
                            )
                            .expressionAttributeNames(
                                    Map.of("#status", "status")
                            )
                            .expressionAttributeValues(values)
                            .build()
            );

            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    private static String required(
            Map<String, AttributeValue> item,
            String key
    ) {
        AttributeValue value = item.get(key);

        if (value == null || value.s() == null || value.s().isBlank()) {
            throw new IllegalStateException(
                    "Missing DynamoDB attribute: " + key
            );
        }

        return value.s();
    }
}

package com.cloudscale.payment;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.Map;

public class DynamoDbPaymentRepository
        implements PaymentRepository {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DynamoDbPaymentRepository(
            DynamoDbClient dynamoDbClient,
            String tableName
    ) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public boolean markPaymentStatusIfPending(
            String orderId,
            String targetStatus,
            Instant updatedAt
    ) {
        UpdateItemRequest request =
                UpdateItemRequest.builder()
                        .tableName(tableName)
                        .key(
                                Map.of(
                                        "PK",
                                        stringValue(
                                                "ORDER#" + orderId
                                        ),
                                        "SK",
                                        stringValue("ORDER")
                                )
                        )
                        .updateExpression(
                                "SET paymentStatus = :targetStatus, "
                                        + "updatedAt = :updatedAt"
                        )
                        .conditionExpression(
                                "attribute_exists(PK) "
                                        + "AND paymentStatus = :pending"
                        )
                        .expressionAttributeValues(
                                Map.of(
                                        ":targetStatus",
                                        stringValue(targetStatus),
                                        ":updatedAt",
                                        stringValue(
                                                updatedAt.toString()
                                        ),
                                        ":pending",
                                        stringValue("PENDING")
                                )
                        )
                        .build();

        try {
            dynamoDbClient.updateItem(request);
            return true;
        } catch (ConditionalCheckFailedException exception) {
            /*
             * Another invocation already moved the order out
             * of PENDING. Treat this as an idempotent duplicate.
             */
            return false;
        }
    }

    private static AttributeValue stringValue(
            String value
    ) {
        return AttributeValue.builder()
                .s(value)
                .build();
    }
}
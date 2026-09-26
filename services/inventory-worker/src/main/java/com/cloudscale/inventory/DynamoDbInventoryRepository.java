package com.cloudscale.inventory;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class DynamoDbInventoryRepository implements InventoryRepository {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DynamoDbInventoryRepository(
            DynamoDbClient dynamoDbClient,
            String tableName
    ) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public boolean createInventoryAndOutboxIfPending(
            String orderId,
            String targetStatus,
            Instant updatedAt,
            InventoryOutboxRecord outboxRecord
    ) {
        Map<String, AttributeValue> orderKey = Map.of(
                "PK", AttributeValue.builder()
                        .s("ORDER#" + orderId)
                        .build(),
                "SK", AttributeValue.builder()
                        .s("ORDER")
                        .build()
        );

        Map<String, AttributeValue> updateValues = new HashMap<>();
        updateValues.put(
                ":pending",
                AttributeValue.builder().s("PENDING").build()
        );
        updateValues.put(
                ":target",
                AttributeValue.builder().s(targetStatus).build()
        );
        updateValues.put(
                ":updatedAt",
                AttributeValue.builder().s(updatedAt.toString()).build()
        );

        Update update = Update.builder()
                .tableName(tableName)
                .key(orderKey)
                .updateExpression(
                        "SET inventoryStatus = :target, updatedAt = :updatedAt"
                )
                .conditionExpression(
                        "attribute_exists(PK) AND inventoryStatus = :pending"
                )
                .expressionAttributeValues(updateValues)
                .build();

        Map<String, AttributeValue> outboxItem = new HashMap<>();
        outboxItem.put(
                "PK",
                AttributeValue.builder()
                        .s("OUTBOX#INVENTORY#" + outboxRecord.orderId())
                        .build()
        );
        outboxItem.put(
                "SK",
                AttributeValue.builder().s("EVENT").build()
        );
        outboxItem.put(
                "entityType",
                AttributeValue.builder().s("INVENTORY_OUTBOX").build()
        );
        outboxItem.put(
                "orderId",
                AttributeValue.builder().s(outboxRecord.orderId()).build()
        );
        outboxItem.put(
                "eventId",
                AttributeValue.builder().s(outboxRecord.eventId()).build()
        );
        outboxItem.put(
                "eventType",
                AttributeValue.builder().s(outboxRecord.eventType()).build()
        );
        outboxItem.put(
                "eventPayload",
                AttributeValue.builder().s(outboxRecord.eventPayload()).build()
        );
        outboxItem.put(
                "status",
                AttributeValue.builder().s(outboxRecord.status()).build()
        );
        outboxItem.put(
                "createdAt",
                AttributeValue.builder().s(outboxRecord.createdAt().toString()).build()
        );

        Put put = Put.builder()
                .tableName(tableName)
                .item(outboxItem)
                .conditionExpression("attribute_not_exists(PK)")
                .build();

        try {
            dynamoDbClient.transactWriteItems(
                    TransactWriteItemsRequest.builder()
                            .transactItems(
                                    TransactWriteItem.builder()
                                            .update(update)
                                            .build(),
                                    TransactWriteItem.builder()
                                            .put(put)
                                            .build()
                            )
                            .build()
            );

            return true;
        } catch (TransactionCanceledException e) {
            return false;
        }
    }

    @Override
    public Optional<InventoryOutboxRecord> findPendingOutbox(String orderId) {
        Map<String, AttributeValue> key = Map.of(
                "PK",
                AttributeValue.builder()
                        .s("OUTBOX#INVENTORY#" + orderId)
                        .build(),
                "SK",
                AttributeValue.builder()
                        .s("EVENT")
                        .build()
        );

        Map<String, AttributeValue> item =
                dynamoDbClient.getItem(
                        GetItemRequest.builder()
                                .tableName(tableName)
                                .key(key)
                                .consistentRead(true)
                                .build()
                )
                .item();

        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }

        String status = item.get("status").s();

        if (!InventoryOutboxRecord.PENDING.equals(status)) {
            return Optional.empty();
        }

        return Optional.of(
                new InventoryOutboxRecord(
                        item.get("orderId").s(),
                        item.get("eventId").s(),
                        item.get("eventType").s(),
                        item.get("eventPayload").s(),
                        status,
                        Instant.parse(item.get("createdAt").s())
                )
        );
    }

    @Override
    public boolean markOutboxPublished(
            String orderId,
            String eventId,
            Instant publishedAt
    ) {
        Map<String, AttributeValue> key = Map.of(
                "PK",
                AttributeValue.builder()
                        .s("OUTBOX#INVENTORY#" + orderId)
                        .build(),
                "SK",
                AttributeValue.builder()
                        .s("EVENT")
                        .build()
        );

        Map<String, AttributeValue> values = new HashMap<>();
        values.put(
                ":published",
                AttributeValue.builder()
                        .s(InventoryOutboxRecord.PUBLISHED)
                        .build()
        );
        values.put(
                ":publishedAt",
                AttributeValue.builder()
                        .s(publishedAt.toString())
                        .build()
        );
        values.put(
                ":pending",
                AttributeValue.builder()
                        .s(InventoryOutboxRecord.PENDING)
                        .build()
        );
        values.put(
                ":eventId",
                AttributeValue.builder()
                        .s(eventId)
                        .build()
        );

        try {
            dynamoDbClient.updateItem(
                    software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.builder()
                            .tableName(tableName)
                            .key(key)
                            .updateExpression(
                                    "SET #status = :published, publishedAt = :publishedAt"
                            )
                            .conditionExpression(
                                    "#status = :pending AND eventId = :eventId"
                            )
                            .expressionAttributeNames(
                                    Map.of("#status", "status")
                            )
                            .expressionAttributeValues(values)
                            .build()
            );

            return true;
        } catch (software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException e) {
            return false;
        }
    }
}

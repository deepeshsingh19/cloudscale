package com.cloudscale.payment;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class DynamoDbPaymentRepository
        implements PaymentRepository {

    private static final String OUTBOX_ENTITY =
            "PAYMENT_OUTBOX";

    private static final String OUTBOX_SK =
            "EVENT";

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
    public boolean createPaymentAndOutboxIfPending(
            String orderId,
            String targetStatus,
            Instant updatedAt,
            PaymentOutboxRecord outboxRecord
    ) {
        UpdateItemRequest updateOrder =
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

        PutOutboxValues outbox =
                buildOutboxItem(outboxRecord);

        software.amazon.awssdk.services.dynamodb.model.Put put =
                software.amazon.awssdk.services.dynamodb.model.Put
                        .builder()
                        .tableName(tableName)
                        .item(outbox.item())
                        .conditionExpression(
                                "attribute_not_exists(PK)"
                        )
                        .build();

        TransactWriteItem orderUpdate =
                TransactWriteItem.builder()
                        .update(
                                software.amazon.awssdk.services.dynamodb
                                        .model.Update.builder()
                                        .tableName(tableName)
                                        .key(
                                                updateOrder.key()
                                        )
                                        .updateExpression(
                                                updateOrder.updateExpression()
                                        )
                                        .conditionExpression(
                                                updateOrder.conditionExpression()
                                        )
                                        .expressionAttributeValues(
                                                updateOrder
                                                        .expressionAttributeValues()
                                        )
                                        .build()
                        )
                        .build();

        TransactWriteItem outboxWrite =
                TransactWriteItem.builder()
                        .put(put)
                        .build();

        try {
            dynamoDbClient.transactWriteItems(
                    TransactWriteItemsRequest.builder()
                            .transactItems(
                                    orderUpdate,
                                    outboxWrite
                            )
                            .build()
            );

            return true;

        } catch (TransactionCanceledException exception) {
            if (hasConditionalFailure(exception)) {
                return false;
            }

            throw exception;
        }
    }

    @Override
    public Optional<PaymentOutboxRecord> findPendingOutbox(
            String orderId
    ) {
        GetItemRequest request =
                GetItemRequest.builder()
                        .tableName(tableName)
                        .key(
                                Map.of(
                                        "PK",
                                        stringValue(
                                                "OUTBOX#PAYMENT#"
                                                        + orderId
                                        ),
                                        "SK",
                                        stringValue(OUTBOX_SK)
                                )
                        )
                        .build();

        GetItemResponse response =
                dynamoDbClient.getItem(request);

        if (!response.hasItem()
                || response.item().isEmpty()) {
            return Optional.empty();
        }

        Map<String, AttributeValue> item =
                response.item();

        String status =
                requiredString(item, "status");

        if (!PaymentOutboxRecord.PENDING.equals(status)) {
            return Optional.empty();
        }

        return Optional.of(
                new PaymentOutboxRecord(
                        requiredString(item, "orderId"),
                        requiredString(item, "eventId"),
                        requiredString(item, "eventType"),
                        requiredString(item, "eventPayload"),
                        status,
                        Instant.parse(
                                requiredString(
                                        item,
                                        "createdAt"
                                )
                        )
                )
        );
    }

    @Override
    public boolean markOutboxPublished(
            String orderId,
            String eventId,
            Instant publishedAt
    ) {
        try {
            dynamoDbClient.updateItem(
                    UpdateItemRequest.builder()
                            .tableName(tableName)
                            .key(
                                    Map.of(
                                            "PK",
                                            stringValue(
                                                    "OUTBOX#PAYMENT#"
                                                            + orderId
                                            ),
                                            "SK",
                                            stringValue(OUTBOX_SK)
                                    )
                            )
                            .updateExpression(
                                    "SET #status = :published, "
                                            + "publishedAt = :publishedAt"
                            )
                            .conditionExpression(
                                    "#status = :pending "
                                            + "AND eventId = :eventId"
                            )
                            .expressionAttributeNames(
                                    Map.of(
                                            "#status",
                                            "status"
                                    )
                            )
                            .expressionAttributeValues(
                                    Map.of(
                                            ":published",
                                            stringValue(
                                                    PaymentOutboxRecord.PUBLISHED
                                            ),
                                            ":pending",
                                            stringValue(
                                                    PaymentOutboxRecord.PENDING
                                            ),
                                            ":eventId",
                                            stringValue(eventId),
                                            ":publishedAt",
                                            stringValue(
                                                    publishedAt.toString()
                                            )
                                    )
                            )
                            .build()
            );

            return true;

        } catch (
                software.amazon.awssdk.services.dynamodb.model
                        .ConditionalCheckFailedException exception
        ) {
            return false;
        }
    }

    private PutOutboxValues buildOutboxItem(
            PaymentOutboxRecord event
    ) {
        Map<String, AttributeValue> item =
                new HashMap<>();

        item.put(
                "PK",
                stringValue(
                        "OUTBOX#PAYMENT#"
                                + event.orderId()
                )
        );

        item.put(
                "SK",
                stringValue(OUTBOX_SK)
        );

        item.put(
                "entityType",
                stringValue(OUTBOX_ENTITY)
        );

        item.put(
                "orderId",
                stringValue(event.orderId())
        );

        item.put(
                "eventId",
                stringValue(event.eventId())
        );

        item.put(
                "eventType",
                stringValue(event.eventType())
        );

        item.put(
                "eventPayload",
                stringValue(event.eventPayload())
        );

        item.put(
                "status",
                stringValue(event.status())
        );

        item.put(
                "createdAt",
                stringValue(
                        event.createdAt().toString()
                )
        );

        return new PutOutboxValues(item);
    }

    private boolean hasConditionalFailure(
            TransactionCanceledException exception
    ) {
        if (exception.cancellationReasons() == null) {
            return false;
        }

        return exception.cancellationReasons()
                .stream()
                .anyMatch(reason ->
                        "ConditionalCheckFailed".equals(
                                reason.code()
                        )
                );
    }

    private String requiredString(
            Map<String, AttributeValue> item,
            String key
    ) {
        AttributeValue value =
                item.get(key);

        if (value == null
                || value.s() == null
                || value.s().isBlank()) {
            throw new IllegalStateException(
                    "Missing DynamoDB attribute: "
                            + key
            );
        }

        return value.s();
    }

    private static AttributeValue stringValue(
            String value
    ) {
        return AttributeValue.builder()
                .s(value)
                .build();
    }

    private record PutOutboxValues(
            Map<String, AttributeValue> item
    ) {
    }
}
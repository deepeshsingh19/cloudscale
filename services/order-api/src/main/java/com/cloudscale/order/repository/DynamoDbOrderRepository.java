package com.cloudscale.order.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cloudscale.order.model.InventoryStatus;
import com.cloudscale.order.model.Order;
import com.cloudscale.order.model.OrderItem;
import com.cloudscale.order.model.OrderStatus;
import com.cloudscale.order.model.PaymentStatus;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

public class DynamoDbOrderRepository implements OrderRepository {

    private static final String ORDER_SK = "ORDER";
    private static final String IDEMPOTENCY_SK = "IDEMPOTENCY";

    private static final String ORDER_ENTITY = "ORDER";
    private static final String IDEMPOTENCY_ENTITY = "IDEMPOTENCY";

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DynamoDbOrderRepository(
            DynamoDbClient dynamoDbClient,
            String tableName
    ) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public CreateOrderResult createAtomically(
            Order order,
            String idempotencyKey,
            long idempotencyExpiresAt
    ) {
        Optional<String> existingOrderId =
                findOrderIdByIdempotencyKey(
                        idempotencyKey
                );

        if (existingOrderId.isPresent()) {
            Order existingOrder =
                    findById(existingOrderId.get())
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Idempotency record points to missing order: "
                                                    + existingOrderId.get()
                                    )
                            );

            return new CreateOrderResult(
                    existingOrder,
                    false
            );
        }

        Map<String, AttributeValue> orderItem =
                toDynamoItem(order);

        Map<String, AttributeValue> idempotencyItem =
                Map.of(
                        "PK", stringValue(
                                "IDEMPOTENCY#"
                                        + idempotencyKey
                        ),
                        "SK", stringValue(
                                IDEMPOTENCY_SK
                        ),
                        "entityType", stringValue(
                                IDEMPOTENCY_ENTITY
                        ),
                        "idempotencyKey", stringValue(
                                idempotencyKey
                        ),
                        "orderId", stringValue(
                                order.orderId()
                        ),
                        "expiresAt", numberValue(
                                idempotencyExpiresAt
                        )
                );

        Put orderPut = Put.builder()
                .tableName(tableName)
                .item(orderItem)
                .conditionExpression(
                        "attribute_not_exists(PK)"
                )
                .build();

        Put idempotencyPut = Put.builder()
                .tableName(tableName)
                .item(idempotencyItem)
                .conditionExpression(
                        "attribute_not_exists(PK)"
                )
                .build();

        TransactWriteItem orderWrite =
                TransactWriteItem.builder()
                        .put(orderPut)
                        .build();

        TransactWriteItem idempotencyWrite =
                TransactWriteItem.builder()
                        .put(idempotencyPut)
                        .build();

        TransactWriteItemsRequest request =
                TransactWriteItemsRequest.builder()
                        .transactItems(
                                orderWrite,
                                idempotencyWrite
                        )
                        .build();

        try {
            dynamoDbClient.transactWriteItems(
                    request
            );

            return new CreateOrderResult(
                    order,
                    true
            );

        } catch (TransactionCanceledException exception) {

            /*
             * The transaction may have been cancelled because
             * another request won the idempotency race.
             *
             * Re-read the idempotency record before deciding that
             * this was a duplicate request.
             */
            Optional<String> existingAfterConflict =
                    findOrderIdByIdempotencyKey(
                            idempotencyKey
                    );

            if (existingAfterConflict.isPresent()) {
                Order existingOrder =
                        findById(
                                existingAfterConflict.get()
                        ).orElseThrow(() ->
                                new IllegalStateException(
                                        "Idempotency record points to missing order: "
                                                + existingAfterConflict.get()
                                )
                        );

                return new CreateOrderResult(
                        existingOrder,
                        false
                );
            }

            throw exception;
        }
    }

    @Override
    public Order save(Order order) {
        dynamoDbClient.putItem(
                PutItemRequest.builder()
                        .tableName(tableName)
                        .item(toDynamoItem(order))
                        .build()
        );

        return order;
    }

    @Override
    public Optional<Order> findById(String orderId) {
        GetItemRequest request =
                GetItemRequest.builder()
                        .tableName(tableName)
                        .key(
                                Map.of(
                                        "PK",
                                        stringValue(
                                                "ORDER#"
                                                        + orderId
                                        ),
                                        "SK",
                                        stringValue(
                                                ORDER_SK
                                        )
                                )
                        )
                        .build();

        var response =
                dynamoDbClient.getItem(request);

        if (!response.hasItem()
                || response.item().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(
                fromDynamoItem(
                        response.item()
                )
        );
    }

    @Override
    public List<Order> findAll() {
        ScanRequest request =
                ScanRequest.builder()
                        .tableName(tableName)
                        .filterExpression(
                                "entityType = :entityType"
                        )
                        .expressionAttributeValues(
                                Map.of(
                                        ":entityType",
                                        stringValue(
                                                ORDER_ENTITY
                                        )
                                )
                        )
                        .build();

        ScanResponse response =
                dynamoDbClient.scan(request);

        return response.items()
                .stream()
                .map(this::fromDynamoItem)
                .toList();
    }

    private Optional<String> findOrderIdByIdempotencyKey(
            String idempotencyKey
    ) {
        GetItemRequest request =
                GetItemRequest.builder()
                        .tableName(tableName)
                        .key(
                                Map.of(
                                        "PK",
                                        stringValue(
                                                "IDEMPOTENCY#"
                                                        + idempotencyKey
                                        ),
                                        "SK",
                                        stringValue(
                                                IDEMPOTENCY_SK
                                        )
                                )
                        )
                        .projectionExpression(
                                "orderId"
                        )
                        .build();

        var response =
                dynamoDbClient.getItem(request);

        if (!response.hasItem()
                || response.item().isEmpty()) {
            return Optional.empty();
        }

        AttributeValue orderId =
                response.item().get("orderId");

        if (orderId == null
                || orderId.s() == null
                || orderId.s().isBlank()) {
            return Optional.empty();
        }

        return Optional.of(
                orderId.s()
        );
    }

    private Map<String, AttributeValue> toDynamoItem(
        Order order
        ) {
        Map<String, AttributeValue> item =
                new java.util.HashMap<>();

        List<AttributeValue> items =
                order.items()
                        .stream()
                        .map(this::toDynamoItem)
                        .toList();

        item.put(
                "PK",
                stringValue(
                        "ORDER#" + order.orderId()
                )
        );

        item.put(
                "SK",
                stringValue(
                        ORDER_SK
                )
        );

        item.put(
                "entityType",
                stringValue(
                        ORDER_ENTITY
                )
        );

        item.put(
                "orderId",
                stringValue(
                        order.orderId()
                )
        );

        item.put(
                "customerId",
                stringValue(
                        order.customerId()
                )
        );

        item.put(
                "items",
                AttributeValue.builder()
                        .l(items)
                        .build()
        );

        item.put(
                "totalAmount",
                stringValue(
                        order.totalAmount()
                                .toPlainString()
                )
        );

        item.put(
                "currency",
                stringValue(
                        order.currency()
                )
        );

        item.put(
                "status",
                stringValue(
                        order.status().name()
                )
        );

        item.put(
                "paymentStatus",
                stringValue(
                        order.paymentStatus().name()
                )
        );

        item.put(
                "inventoryStatus",
                stringValue(
                        order.inventoryStatus().name()
                )
        );

        item.put(
                "createdAt",
                stringValue(
                        order.createdAt().toString()
                )
        );

        item.put(
                "updatedAt",
                stringValue(
                        order.updatedAt().toString()
                )
        );

        return item;
        }

    private AttributeValue toDynamoItem(
            OrderItem item
    ) {
        return AttributeValue.builder()
                .m(
                        Map.of(
                                "productId",
                                stringValue(
                                        item.productId()
                                ),
                                "quantity",
                                numberValue(
                                        item.quantity()
                                ),
                                "unitPrice",
                                stringValue(
                                        item.unitPrice()
                                                .toPlainString()
                                )
                        )
                )
                .build();
    }

    private Order fromDynamoItem(
            Map<String, AttributeValue> item
    ) {
        List<OrderItem> orderItems =
                item.get("items")
                        .l()
                        .stream()
                        .map(this::fromDynamoItem)
                        .toList();

        return new Order(
                requiredString(
                        item,
                        "orderId"
                ),
                requiredString(
                        item,
                        "customerId"
                ),
                orderItems,
                new BigDecimal(
                        requiredString(
                                item,
                                "totalAmount"
                        )
                ),
                requiredString(
                        item,
                        "currency"
                ),
                OrderStatus.valueOf(
                        requiredString(
                                item,
                                "status"
                        )
                ),
                PaymentStatus.valueOf(
                        requiredString(
                                item,
                                "paymentStatus"
                        )
                ),
                InventoryStatus.valueOf(
                        requiredString(
                                item,
                                "inventoryStatus"
                        )
                ),
                java.time.Instant.parse(
                        requiredString(
                                item,
                                "createdAt"
                        )
                ),
                java.time.Instant.parse(
                        requiredString(
                                item,
                                "updatedAt"
                        )
                )
        );
    }

    private OrderItem fromDynamoItem(
            AttributeValue value
    ) {
        Map<String, AttributeValue> map =
                value.m();

        return new OrderItem(
                requiredString(
                        map,
                        "productId"
                ),
                Integer.parseInt(
                        requiredString(
                                map,
                                "quantity"
                        )
                ),
                new BigDecimal(
                        requiredString(
                                map,
                                "unitPrice"
                        )
                )
        );
    }

    private String requiredString(
            Map<String, AttributeValue> item,
            String key
    ) {
        AttributeValue value =
                item.get(key);

        if (value == null) {
            throw new IllegalStateException(
                    "Missing DynamoDB attribute: "
                            + key
            );
        }

        if (value.s() != null) {
            return value.s();
        }

        if (value.n() != null) {
            return value.n();
        }

        throw new IllegalStateException(
                "Unsupported DynamoDB attribute type for: "
                        + key
        );
    }

    private static AttributeValue stringValue(
            String value
    ) {
        return AttributeValue.builder()
                .s(value)
                .build();
    }

    private static AttributeValue numberValue(
            long value
    ) {
        return AttributeValue.builder()
                .n(Long.toString(value))
                .build();
    }

    private static AttributeValue numberValue(
            int value
    ) {
        return AttributeValue.builder()
                .n(Integer.toString(value))
                .build();
    }
}
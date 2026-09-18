package com.cloudscale.order.repository;

import com.cloudscale.order.model.InventoryStatus;
import com.cloudscale.order.model.Order;
import com.cloudscale.order.model.OrderItem;
import com.cloudscale.order.model.OrderStatus;
import com.cloudscale.order.model.PaymentStatus;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DynamoDbOrderRepository implements OrderRepository {

    private static final String ORDER_SK = "ORDER";
    private static final String IDEMPOTENCY_SK = "IDEMPOTENCY";

    private static final String ENTITY_ORDER = "ORDER";
    private static final String ENTITY_IDEMPOTENCY = "IDEMPOTENCY";

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
    public Order createAtomically(
            Order order,
            String idempotencyKey,
            long idempotencyExpiresAt
    ) {
        Optional<String> existingOrderId =
                findOrderIdByIdempotencyKey(idempotencyKey);

        if (existingOrderId.isPresent()) {
            return findById(existingOrderId.get())
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "Idempotency record references missing order"
                            ));
        }

        Map<String, AttributeValue> orderItem =
                toOrderItem(order);

        Map<String, AttributeValue> idempotencyItem =
                toIdempotencyItem(
                        idempotencyKey,
                        order.orderId(),
                        idempotencyExpiresAt
                );

        TransactWriteItem orderWrite =
                TransactWriteItem.builder()
                        .put(put -> put
                                .tableName(tableName)
                                .item(orderItem))
                        .build();

        TransactWriteItem idempotencyWrite =
                TransactWriteItem.builder()
                        .put(put -> put
                                .tableName(tableName)
                                .item(idempotencyItem)
                                .conditionExpression(
                                        "attribute_not_exists(PK)"
                                ))
                        .build();

        try {
            dynamoDbClient.transactWriteItems(
                    TransactWriteItemsRequest.builder()
                            .transactItems(
                                    orderWrite,
                                    idempotencyWrite
                            )
                            .build()
            );

            return order;

        } catch (TransactionCanceledException exception) {

            /*
             * Another request may have created the same
             * idempotency key concurrently.
             *
             * Resolve the existing order and return it.
             */
            return findOrderIdByIdempotencyKey(idempotencyKey)
                    .flatMap(this::findById)
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "Order creation transaction was cancelled"
                            ));
        }
    }

    @Override
    public Order save(Order order) {
        dynamoDbClient.putItem(
                PutItemRequest.builder()
                        .tableName(tableName)
                        .item(toOrderItem(order))
                        .build()
        );

        return order;
    }

    @Override
    public Optional<Order> findById(String orderId) {
        Map<String, AttributeValue> key =
                Map.of(
                        "PK",
                        AttributeValue.builder()
                                .s("ORDER#" + orderId)
                                .build(),
                        "SK",
                        AttributeValue.builder()
                                .s(ORDER_SK)
                                .build()
                );

        var response =
                dynamoDbClient.getItem(
                        GetItemRequest.builder()
                                .tableName(tableName)
                                .key(key)
                                .consistentRead(true)
                                .build()
                );

        if (!response.hasItem()) {
            return Optional.empty();
        }

        return Optional.of(fromOrderItem(response.item()));
    }

    @Override
    public List<Order> findAll() {
        var response =
                dynamoDbClient.scan(
                        ScanRequest.builder()
                                .tableName(tableName)
                                .filterExpression(
                                        "entityType = :entityType"
                                )
                                .expressionAttributeValues(
                                        Map.of(
                                                ":entityType",
                                                AttributeValue.builder()
                                                        .s(ENTITY_ORDER)
                                                        .build()
                                        )
                                )
                                .build()
                );

        return response.items()
                .stream()
                .map(this::fromOrderItem)
                .sorted(
                        Comparator.comparing(Order::createdAt)
                                .reversed()
                )
                .toList();
    }

    private Optional<String> findOrderIdByIdempotencyKey(
            String idempotencyKey
    ) {
        Map<String, AttributeValue> key =
                Map.of(
                        "PK",
                        AttributeValue.builder()
                                .s("IDEMPOTENCY#" + idempotencyKey)
                                .build(),
                        "SK",
                        AttributeValue.builder()
                                .s(IDEMPOTENCY_SK)
                                .build()
                );

        var response =
                dynamoDbClient.getItem(
                        GetItemRequest.builder()
                                .tableName(tableName)
                                .key(key)
                                .consistentRead(true)
                                .build()
                );

        if (!response.hasItem()) {
            return Optional.empty();
        }

        AttributeValue orderId =
        response.item().get("orderId");

        if (orderId == null
                || orderId.s() == null
                || orderId.s().isBlank()) {
            return Optional.empty();
        }

        return Optional.of(orderId.s());
    }

    private Map<String, AttributeValue> toOrderItem(
            Order order
    ) {
        Map<String, AttributeValue> item =
                new HashMap<>();

        item.put(
                "PK",
                stringValue("ORDER#" + order.orderId())
        );

        item.put(
                "SK",
                stringValue(ORDER_SK)
        );

        item.put(
                "entityType",
                stringValue(ENTITY_ORDER)
        );

        item.put(
                "orderId",
                stringValue(order.orderId())
        );

        item.put(
                "customerId",
                stringValue(order.customerId())
        );

        item.put(
                "totalAmount",
                stringValue(order.totalAmount().toPlainString())
        );

        item.put(
                "currency",
                stringValue(order.currency())
        );

        item.put(
                "status",
                stringValue(order.status().name())
        );

        item.put(
                "paymentStatus",
                stringValue(order.paymentStatus().name())
        );

        item.put(
                "inventoryStatus",
                stringValue(order.inventoryStatus().name())
        );

        item.put(
                "createdAt",
                stringValue(order.createdAt().toString())
        );

        item.put(
                "updatedAt",
                stringValue(order.updatedAt().toString())
        );

        List<AttributeValue> items =
                order.items()
                        .stream()
                        .map(this::toOrderItemAttribute)
                        .toList();

        item.put(
                "items",
                AttributeValue.builder()
                        .l(items)
                        .build()
        );

        return item;
    }

    private Map<String, AttributeValue> toIdempotencyItem(
            String idempotencyKey,
            String orderId,
            long expiresAt
    ) {
        Map<String, AttributeValue> item =
                new HashMap<>();

        item.put(
                "PK",
                stringValue(
                        "IDEMPOTENCY#" + idempotencyKey
                )
        );

        item.put(
                "SK",
                stringValue(IDEMPOTENCY_SK)
        );

        item.put(
                "entityType",
                stringValue(ENTITY_IDEMPOTENCY)
        );

        item.put(
                "idempotencyKey",
                stringValue(idempotencyKey)
        );

        item.put(
                "orderId",
                stringValue(orderId)
        );

        item.put(
                "expiresAt",
                AttributeValue.builder()
                        .n(Long.toString(expiresAt))
                        .build()
        );

        return item;
    }

    private AttributeValue toOrderItemAttribute(
            OrderItem item
    ) {
        Map<String, AttributeValue> map =
                new HashMap<>();

        map.put(
                "productId",
                stringValue(item.productId())
        );

        map.put(
                "quantity",
                AttributeValue.builder()
                        .n(Integer.toString(item.quantity()))
                        .build()
        );

        map.put(
                "unitPrice",
                stringValue(
                        item.unitPrice().toPlainString()
                )
        );

        return AttributeValue.builder()
                .m(map)
                .build();
    }

    private Order fromOrderItem(
            Map<String, AttributeValue> item
    ) {
        List<OrderItem> orderItems =
                new ArrayList<>();

        AttributeValue itemsAttribute =
                item.get("items");

        if (itemsAttribute != null &&
                itemsAttribute.hasL()) {

            for (AttributeValue value :
                    itemsAttribute.l()) {

                Map<String, AttributeValue> map =
                        value.m();

                orderItems.add(
                        new OrderItem(
                                map.get("productId").s(),
                                Integer.parseInt(
                                        map.get("quantity").n()
                                ),
                                new BigDecimal(
                                        map.get("unitPrice").s()
                                )
                        )
                );
            }
        }

        return new Order(
                item.get("orderId").s(),
                item.get("customerId").s(),
                orderItems,
                new BigDecimal(
                        item.get("totalAmount").s()
                ),
                item.get("currency").s(),
                OrderStatus.valueOf(
                        item.get("status").s()
                ),
                PaymentStatus.valueOf(
                        item.get("paymentStatus").s()
                ),
                InventoryStatus.valueOf(
                        item.get("inventoryStatus").s()
                ),
                Instant.parse(
                        item.get("createdAt").s()
                ),
                Instant.parse(
                        item.get("updatedAt").s()
                )
        );
    }

    private AttributeValue stringValue(String value) {
        return AttributeValue.builder()
                .s(value)
                .build();
    }
}
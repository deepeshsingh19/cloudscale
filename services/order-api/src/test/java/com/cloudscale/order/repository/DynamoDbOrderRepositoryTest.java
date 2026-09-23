package com.cloudscale.order.repository;

import com.cloudscale.order.model.InventoryStatus;
import com.cloudscale.order.model.Order;
import com.cloudscale.order.model.OrderItem;
import com.cloudscale.order.model.OrderStatus;
import com.cloudscale.order.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoDbOrderRepositoryTest {

    private static final String TABLE_NAME =
            "cloudscale-dev-orders";

    private final DynamoDbClient dynamoDbClient =
            mock(DynamoDbClient.class);

    private final DynamoDbOrderRepository repository =
            new DynamoDbOrderRepository(
                    dynamoDbClient,
                    TABLE_NAME
            );

    @Test
    void shouldCreateOrderAndIdempotencyRecordAtomically() {
        when(
                dynamoDbClient.getItem(
                        any(GetItemRequest.class)
                )
        ).thenReturn(
                GetItemResponse.builder()
                        .build()
        );

        when(
                dynamoDbClient.transactWriteItems(
                        any(TransactWriteItemsRequest.class)
                )
        ).thenReturn(
                TransactWriteItemsResponse.builder()
                        .build()
        );

        Order order = sampleOrder();

        OrderRepository.CreateOrderResult result =
                repository.createAtomically(
                        order,
                        "idem-001",
                        123456789L
                );

        assertTrue(result.created());
        assertEquals(order, result.order());

        ArgumentCaptor<TransactWriteItemsRequest> captor =
                ArgumentCaptor.forClass(
                        TransactWriteItemsRequest.class
                );

        verify(dynamoDbClient)
                .transactWriteItems(
                        captor.capture()
                );

        TransactWriteItemsRequest request =
                captor.getValue();

        assertEquals(
                2,
                request.transactItems().size()
        );

        assertEquals(
                "attribute_not_exists(PK)",
                request.transactItems()
                        .get(0)
                        .put()
                        .conditionExpression()
        );

        assertEquals(
                "attribute_not_exists(PK)",
                request.transactItems()
                        .get(1)
                        .put()
                        .conditionExpression()
        );
    }

    @Test
    void shouldReturnExistingOrderForExistingIdempotencyKey() {
        Order order = sampleOrder();

        Map<String, AttributeValue> idempotencyItem =
                Map.of(
                        "orderId",
                        stringValue(order.orderId())
                );

        when(
                dynamoDbClient.getItem(
                        any(GetItemRequest.class)
                )
        ).thenAnswer(invocation -> {
            GetItemRequest request =
                    invocation.getArgument(0);

            if (request.projectionExpression() != null) {
                return GetItemResponse.builder()
                        .item(idempotencyItem)
                        .build();
            }

            return GetItemResponse.builder()
                    .item(toDynamoOrderItem(order))
                    .build();
        });

        OrderRepository.CreateOrderResult result =
                repository.createAtomically(
                        order,
                        "idem-001",
                        123456789L
                );

        assertFalse(result.created());
        assertEquals(order, result.order());
    }

    @Test
    void shouldFindOrderById() {
        Order order = sampleOrder();

        when(
                dynamoDbClient.getItem(
                        any(GetItemRequest.class)
                )
        ).thenReturn(
                GetItemResponse.builder()
                        .item(toDynamoOrderItem(order))
                        .build()
        );

        var result =
                repository.findById(
                        order.orderId()
                );

        assertTrue(result.isPresent());
        assertEquals(order, result.get());
    }

    private Order sampleOrder() {
        Instant now =
                Instant.parse(
                        "2026-09-23T10:00:00Z"
                );

        return new Order(
                "ORD-001",
                "CUS-001",
                List.of(
                        new OrderItem(
                                "PROD-001",
                                2,
                                new BigDecimal("749.50")
                        )
                ),
                new BigDecimal("1499.00"),
                "INR",
                OrderStatus.CREATED,
                PaymentStatus.PENDING,
                InventoryStatus.PENDING,
                now,
                now
        );
    }

    private Map<String, AttributeValue> toDynamoOrderItem(
            Order order
    ) {
        Map<String, AttributeValue> item =
                new HashMap<>();

        item.put(
                "PK",
                stringValue(
                        "ORDER#" + order.orderId()
                )
        );

        item.put(
                "SK",
                stringValue("ORDER")
        );

        item.put(
                "entityType",
                stringValue("ORDER")
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
                "items",
                AttributeValue.builder()
                        .l(
                                List.of(
                                        AttributeValue.builder()
                                                .m(
                                                        Map.of(
                                                                "productId",
                                                                stringValue("PROD-001"),
                                                                "quantity",
                                                                AttributeValue.builder()
                                                                        .n("2")
                                                                        .build(),
                                                                "unitPrice",
                                                                stringValue("749.50")
                                                        )
                                                )
                                                .build()
                                )
                        )
                        .build()
        );

        item.put(
                "totalAmount",
                stringValue("1499.00")
        );

        item.put(
                "currency",
                stringValue("INR")
        );

        item.put(
                "status",
                stringValue("CREATED")
        );

        item.put(
                "paymentStatus",
                stringValue("PENDING")
        );

        item.put(
                "inventoryStatus",
                stringValue("PENDING")
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

    private static AttributeValue stringValue(
            String value
    ) {
        return AttributeValue.builder()
                .s(value)
                .build();
    }
}
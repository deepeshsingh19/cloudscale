package com.cloudscale.order.service;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cloudscale.order.dto.CreateOrderRequest;
import com.cloudscale.order.model.InventoryStatus;
import com.cloudscale.order.model.Order;
import com.cloudscale.order.model.OrderStatus;
import com.cloudscale.order.model.PaymentStatus;
import com.cloudscale.order.repository.IdempotencyRepository;
import com.cloudscale.order.repository.InMemoryIdempotencyRepository;
import com.cloudscale.order.repository.InMemoryOrderRepository;
import com.cloudscale.order.repository.OrderRepository;

class OrderServiceTest {

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        OrderRepository orderRepository =
                new InMemoryOrderRepository();

        IdempotencyRepository idempotencyRepository =
                new InMemoryIdempotencyRepository();

        orderService = new OrderService(
                orderRepository,
                idempotencyRepository
        );
    }

    @Test
    void shouldCreateOrderAndCalculateTotal() {
        CreateOrderRequest request = new CreateOrderRequest(
                "CUS-1001",
                List.of(
                        new CreateOrderRequest.OrderItemRequest(
                                "PROD-001",
                                2,
                                new BigDecimal("749.50")
                        ),
                        new CreateOrderRequest.OrderItemRequest(
                                "PROD-002",
                                1,
                                new BigDecimal("500.00")
                        )
                ),
                "INR"
        );

        Order order =
                orderService.createOrder(request, "idem-001");

        assertNotNull(order.orderId());
        assertEquals("CUS-1001", order.customerId());
        assertEquals(
                new BigDecimal("1999.00"),
                order.totalAmount()
        );
        assertEquals(OrderStatus.CREATED, order.status());
        assertEquals(
                PaymentStatus.PENDING,
                order.paymentStatus()
        );
        assertEquals(
                InventoryStatus.PENDING,
                order.inventoryStatus()
        );
    }

    @Test
    void shouldReturnSameOrderForDuplicateIdempotencyKey() {
        CreateOrderRequest request = new CreateOrderRequest(
                "CUS-1001",
                List.of(
                        new CreateOrderRequest.OrderItemRequest(
                                "PROD-001",
                                1,
                                new BigDecimal("100.00")
                        )
                ),
                "INR"
        );

        Order first =
                orderService.createOrder(request, "idem-duplicate");

        Order second =
                orderService.createOrder(request, "idem-duplicate");

        assertEquals(first.orderId(), second.orderId());
        assertEquals(
                first.createdAt(),
                second.createdAt()
        );
    }

    @Test
    void shouldCreateDifferentOrdersForDifferentIdempotencyKeys() {
        CreateOrderRequest request = new CreateOrderRequest(
                "CUS-1001",
                List.of(
                        new CreateOrderRequest.OrderItemRequest(
                                "PROD-001",
                                1,
                                new BigDecimal("100.00")
                        )
                ),
                "INR"
        );

        Order first =
                orderService.createOrder(request, "idem-001");

        Order second =
                orderService.createOrder(request, "idem-002");

        assertNotEquals(first.orderId(), second.orderId());
    }

    @Test
    void shouldRetrieveCreatedOrder() {
        CreateOrderRequest request = new CreateOrderRequest(
                "CUS-1001",
                List.of(
                        new CreateOrderRequest.OrderItemRequest(
                                "PROD-001",
                                1,
                                new BigDecimal("250.00")
                        )
                ),
                "INR"
        );

        Order created =
                orderService.createOrder(request, "idem-get");

        Order retrieved =
                orderService.getOrder(created.orderId());

        assertEquals(created.orderId(), retrieved.orderId());
        assertEquals(
                created.totalAmount(),
                retrieved.totalAmount()
        );
    }

    @Test
    void shouldReturnAllOrders() {
        CreateOrderRequest request = new CreateOrderRequest(
                "CUS-1001",
                List.of(
                        new CreateOrderRequest.OrderItemRequest(
                                "PROD-001",
                                1,
                                new BigDecimal("100.00")
                        )
                ),
                "INR"
        );

        orderService.createOrder(request, "idem-1");
        orderService.createOrder(request, "idem-2");

        assertEquals(2, orderService.getOrders().size());
    }
}
package com.cloudscale.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cloudscale.order.dto.CreateOrderRequest;
import com.cloudscale.order.event.OrderEventPublisher;
import com.cloudscale.order.model.InventoryStatus;
import com.cloudscale.order.model.Order;
import com.cloudscale.order.model.OrderStatus;
import com.cloudscale.order.model.PaymentStatus;
import com.cloudscale.order.repository.OrderRepository;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService =
                new OrderService(
                        orderRepository,
                        Optional.of(orderEventPublisher)
                );
    }

    @Test
    void shouldCreateOrderAndCalculateTotal() {
        CreateOrderRequest request =
                createRequest();

        when(
                orderRepository.createAtomically(
                        any(Order.class),
                        eq("key-1"),
                        anyLong()
                )
        ).thenAnswer(invocation ->
                new OrderRepository.CreateOrderResult(
                        invocation.getArgument(0),
                        true
                )
        );

        Order order =
                orderService.createOrder(
                        request,
                        "key-1"
                );

        assertEquals(
                new BigDecimal("1499.00"),
                order.totalAmount()
        );

        assertEquals(
                OrderStatus.CREATED,
                order.status()
        );

        assertEquals(
                PaymentStatus.PENDING,
                order.paymentStatus()
        );

        assertEquals(
                InventoryStatus.PENDING,
                order.inventoryStatus()
        );

        verify(orderEventPublisher)
                .publishOrderCreated(order);
    }

    @Test
    void shouldNotPublishEventForDuplicateIdempotencyKey() {
        CreateOrderRequest request =
                createRequest();

        Order existingOrder =
                existingOrder();

        when(
                orderRepository.createAtomically(
                        any(Order.class),
                        eq("duplicate-key"),
                        anyLong()
                )
        ).thenReturn(
                new OrderRepository.CreateOrderResult(
                        existingOrder,
                        false
                )
        );

        Order result =
                orderService.createOrder(
                        request,
                        "duplicate-key"
                );

        assertEquals(
                existingOrder,
                result
        );

        verify(
                orderEventPublisher,
                never()
        ).publishOrderCreated(
                any(Order.class)
        );
    }

    @Test
    void shouldPublishEventForDifferentIdempotencyKeys() {
        CreateOrderRequest request =
                createRequest();

        when(
                orderRepository.createAtomically(
                        any(Order.class),
                        any(String.class),
                        anyLong()
                )
        ).thenAnswer(invocation ->
                new OrderRepository.CreateOrderResult(
                        invocation.getArgument(0),
                        true
                )
        );

        Order first =
                orderService.createOrder(
                        request,
                        "key-1"
                );

        Order second =
                orderService.createOrder(
                        request,
                        "key-2"
                );

        verify(orderEventPublisher)
                .publishOrderCreated(first);

        verify(orderEventPublisher)
                .publishOrderCreated(second);
    }

    @Test
    void shouldReturnExistingOrder() {
        Order order =
                existingOrder();

        when(
                orderRepository.findById(
                        order.orderId()
                )
        ).thenReturn(
                Optional.of(order)
        );

        Order result =
                orderService.getOrder(
                        order.orderId()
                );

        assertEquals(
                order,
                result
        );
    }

    @Test
    void shouldThrowWhenOrderDoesNotExist() {
        when(
                orderRepository.findById(
                        "missing-order"
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        orderService.getOrder(
                                "missing-order"
                        )
        );
    }

    @Test
    void shouldListOrders() {
        Order order =
                existingOrder();

        when(
                orderRepository.findAll()
        ).thenReturn(
                List.of(order)
        );

        List<Order> orders =
                orderService.getOrders();

        assertEquals(
                1,
                orders.size()
        );

        assertEquals(
                order,
                orders.get(0)
        );
    }

    private CreateOrderRequest createRequest() {
        return new CreateOrderRequest(
                "CUS-1001",
                List.of(
                        new CreateOrderRequest.OrderItemRequest(
                                "PROD-001",
                                2,
                                new BigDecimal("749.50")
                        )
                ),
                "INR"
        );
    }

    private Order existingOrder() {
        Instant now =
                Instant.parse(
                        "2026-09-23T10:00:00Z"
                );

        return new Order(
                "ORD-existing",
                "CUS-1001",
                List.of(
                        new com.cloudscale.order.model.OrderItem(
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
}
package com.cloudscale.order.controller;

import com.cloudscale.order.model.InventoryStatus;
import com.cloudscale.order.model.Order;
import com.cloudscale.order.model.OrderStatus;
import com.cloudscale.order.model.PaymentStatus;
import com.cloudscale.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void shouldCreateOrder() throws Exception {

        Order order = sampleOrder("ORD-123");

        when(orderService.createOrder(any(), eq("idem-123")))
                .thenReturn(order);

        mockMvc.perform(
                        post("/orders")
                                .header("Idempotency-Key", "idem-123")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "customerId": "CUS-1001",
                                          "items": [
                                            {
                                              "productId": "PROD-001",
                                              "quantity": 2,
                                              "unitPrice": 749.50
                                            }
                                          ],
                                          "currency": "INR"
                                        }
                                        """)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value("ORD-123"))
                .andExpect(jsonPath("$.customerId").value("CUS-1001"))
                .andExpect(jsonPath("$.totalAmount").value(1499.00))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void shouldGetOrder() throws Exception {

        when(orderService.getOrder("ORD-123"))
                .thenReturn(sampleOrder("ORD-123"));

        mockMvc.perform(get("/orders/ORD-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ORD-123"))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void shouldListOrders() throws Exception {

        when(orderService.getOrders())
                .thenReturn(List.of(
                        sampleOrder("ORD-1"),
                        sampleOrder("ORD-2")
                ));

        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].orderId").value("ORD-1"))
                .andExpect(jsonPath("$[1].orderId").value("ORD-2"));
    }

    @Test
    void shouldRejectInvalidOrder() throws Exception {

        mockMvc.perform(
                        post("/orders")
                                .header("Idempotency-Key", "idem-invalid")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "customerId": "",
                                          "items": [],
                                          "currency": "INR"
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnNotFoundWhenOrderDoesNotExist() throws Exception {

        when(orderService.getOrder("ORD-MISSING"))
                .thenThrow(new IllegalArgumentException(
                        "Order not found: ORD-MISSING"
                ));

        mockMvc.perform(get("/orders/ORD-MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(
                        jsonPath("$.message")
                                .value("Order not found: ORD-MISSING")
                );
    }

    private Order sampleOrder(String orderId) {

        Instant now = Instant.parse(
                "2026-09-17T00:00:00Z"
        );

        return new Order(
                orderId,
                "CUS-1001",
                List.of(),
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
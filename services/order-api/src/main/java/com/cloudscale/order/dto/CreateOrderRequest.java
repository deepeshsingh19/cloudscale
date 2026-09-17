package com.cloudscale.order.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(

        @NotBlank
        String customerId,

        @NotEmpty
        List<@Valid OrderItemRequest> items,

        @NotBlank
        String currency
) {

    public record OrderItemRequest(

            @NotBlank
            String productId,

            @Positive
            int quantity,

            @NotNull
            @DecimalMin(value = "0.01")
            BigDecimal unitPrice
    ) {
    }
}
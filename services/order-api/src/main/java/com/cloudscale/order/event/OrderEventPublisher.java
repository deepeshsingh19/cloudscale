package com.cloudscale.order.event;

import com.cloudscale.order.model.Order;

public interface OrderEventPublisher {

    void publishOrderCreated(Order order);
}
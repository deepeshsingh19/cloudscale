package com.cloudscale.inventory;

public interface InventoryEventPublisher {

    void publish(InventoryResultEvent event);
}

package com.cloudscale.payment;

public interface PaymentEventPublisher {

    void publish(PaymentResultEvent event);
}
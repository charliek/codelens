package com.example.shop.dto;

/** A data-transfer object for orders. */
public class OrderDto {

    private final Long customerId;
    private final double total;

    public OrderDto(Long customerId, double total) {
        this.customerId = customerId;
        this.total = total;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public double getTotal() {
        return total;
    }
}

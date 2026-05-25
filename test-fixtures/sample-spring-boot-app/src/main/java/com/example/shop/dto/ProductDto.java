package com.example.shop.dto;

/** A data-transfer object for products. */
public class ProductDto {

    private final String name;
    private final double price;

    public ProductDto(String name, double price) {
        this.name = name;
        this.price = price;
    }

    public String getName() {
        return name;
    }

    public double getPrice() {
        return price;
    }
}

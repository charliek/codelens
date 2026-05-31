package com.example.shop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** A data-transfer object for products. Carries jakarta.validation constraints. */
public class ProductDto {

    @NotBlank
    private final String name;

    @Positive
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

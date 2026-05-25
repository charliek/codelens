package com.example.shop.service;

import com.example.shop.model.Product;
import java.util.List;

/** Business operations on products (interface + impl pair). */
public interface ProductService {

    List<Product> findAll();

    Product findById(Long id);

    Product create(String name, double price);
}

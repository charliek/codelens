package com.example.shop.repository;

import com.example.shop.model.Product;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for products. */
public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByName(String name);
}

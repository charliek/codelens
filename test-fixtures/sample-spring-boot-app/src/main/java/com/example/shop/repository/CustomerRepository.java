package com.example.shop.repository;

import com.example.shop.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for customers. */
public interface CustomerRepository extends JpaRepository<Customer, Long> {
}

package com.example.shop.repository;

import com.example.shop.model.Order;
import org.springframework.data.repository.CrudRepository;

/** Spring Data repository for orders (extends CrudRepository, not JpaRepository). */
public interface OrderRepository extends CrudRepository<Order, Long> {
}

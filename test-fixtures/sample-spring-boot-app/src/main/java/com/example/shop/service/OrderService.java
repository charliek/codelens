package com.example.shop.service;

import com.example.shop.model.Customer;
import com.example.shop.model.Order;
import com.example.shop.repository.OrderRepository;
import org.springframework.stereotype.Service;

/** Business operations on orders; depends on several other services. */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final CustomerService customerService;
    private final NotificationService notificationService;

    public OrderService(
        OrderRepository orderRepository,
        ProductService productService,
        CustomerService customerService,
        NotificationService notificationService) {
        this.orderRepository = orderRepository;
        this.productService = productService;
        this.customerService = customerService;
        this.notificationService = notificationService;
    }

    public Order placeOrder(Long customerId, double total) {
        Customer customer = customerService.findById(customerId);
        Order order = orderRepository.save(new Order(customer, total));
        notificationService.notify("orders", "order placed");
        return order;
    }

    public long count() {
        return orderRepository.count();
    }
}

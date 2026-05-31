package com.example.shop.service;

import com.example.shop.model.Customer;
import com.example.shop.model.Order;
import com.example.shop.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public Order placeOrder(Long customerId, double total) {
        Customer customer = customerService.findById(customerId);
        Order order = orderRepository.save(new Order(customer, total));
        notificationService.notify("orders", "order placed");
        return order;
    }

    /**
     * SELF-INVOCATION: calls the {@code @Transactional} {@link #placeOrder} via
     * {@code this}, so the transactional proxy is bypassed and no new transaction
     * is started. Spotting this needs the {@code @Transactional} inventory joined
     * with the call graph — an annotation lookup alone misses it.
     */
    public void placeOrders(Long customerId, double[] totals) {
        for (double total : totals) {
            placeOrder(customerId, total);
        }
    }

    public long count() {
        return orderRepository.count();
    }
}

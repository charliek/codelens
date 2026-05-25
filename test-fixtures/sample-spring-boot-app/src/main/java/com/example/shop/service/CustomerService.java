package com.example.shop.service;

import com.example.shop.exception.NotFoundException;
import com.example.shop.model.Customer;
import com.example.shop.repository.CustomerRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** Business operations on customers. */
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final NotificationService notificationService;

    public CustomerService(CustomerRepository customerRepository, NotificationService notificationService) {
        this.customerRepository = customerRepository;
        this.notificationService = notificationService;
    }

    public List<Customer> findAll() {
        return customerRepository.findAll();
    }

    public Customer register(String name, String email) {
        Customer saved = customerRepository.save(new Customer(name, email));
        notificationService.notify("customers", "registered customer");
        return saved;
    }

    public Customer findById(Long id) {
        return customerRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("customer not found"));
    }
}

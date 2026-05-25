package com.example.shop.service;

import com.example.shop.exception.NotFoundException;
import com.example.shop.model.Product;
import com.example.shop.repository.ProductRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** Default {@link ProductService} backed by JPA. */
@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final NotificationService notificationService;

    public ProductServiceImpl(ProductRepository productRepository, NotificationService notificationService) {
        this.productRepository = productRepository;
        this.notificationService = notificationService;
    }

    @Override
    public List<Product> findAll() {
        return productRepository.findAll();
    }

    @Override
    public Product findById(Long id) {
        return productRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("product not found"));
    }

    @Override
    public Product create(String name, double price) {
        Product saved = productRepository.save(new Product(name, price));
        notificationService.notify("products", "created product");
        return saved;
    }
}

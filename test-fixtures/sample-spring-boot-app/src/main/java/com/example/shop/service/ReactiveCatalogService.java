package com.example.shop.service;

import com.example.shop.model.Product;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive path: exposes the catalog as Reactor {@link Mono}/{@link Flux}. This
 * is the "reactive" side of the blocking-vs-reactive xref contrast — references
 * reactor.core.publisher.* and contrasts with {@link InventoryService}.
 */
@Service
public class ReactiveCatalogService {

    private final ProductService productService;

    public ReactiveCatalogService(ProductService productService) {
        this.productService = productService;
    }

    public Flux<Product> streamProducts() {
        return Flux.fromIterable(productService.findAll());
    }

    public Mono<Product> findProduct(Long id) {
        return Mono.justOrEmpty(productService.findById(id));
    }
}

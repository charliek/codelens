package com.example.shop.web;

import com.example.shop.model.Product;
import com.example.shop.service.InventoryService;
import com.example.shop.service.ReactiveCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Reactive REST controller returning Reactor types. */
@RestController
@RequestMapping("/catalog")
public class ReactiveController extends BaseController {

    private final ReactiveCatalogService catalogService;
    private final InventoryService inventoryService;

    public ReactiveController(ReactiveCatalogService catalogService, InventoryService inventoryService) {
        this.catalogService = catalogService;
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public Flux<Product> stream() {
        return catalogService.streamProducts();
    }

    @GetMapping("/{id}")
    public Mono<Product> one(@PathVariable Long id) {
        return catalogService.findProduct(id);
    }

    // ANTIPATTERN: .block() collapses the async pipeline onto the calling
    // (event-loop) thread. Detect with: calls on this method filtered for
    // reactor.core.publisher.Mono.block.
    @GetMapping("/{id}/blocking")
    public Mono<Product> blocking(@PathVariable Long id) {
        Product product = catalogService.findProduct(id).block();
        return Mono.justOrEmpty(product);
    }

    // ANTIPATTERN: blocking JDBC (InventoryService -> javax.sql/java.sql) called
    // directly on a Mono-returning request path.
    @GetMapping("/{id}/stock")
    public Mono<Integer> stock(@PathVariable Long id) {
        int qty = inventoryService.stockLevel(id);
        return Mono.just(qty);
    }
}

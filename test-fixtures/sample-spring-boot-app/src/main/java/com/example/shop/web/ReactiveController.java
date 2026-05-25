package com.example.shop.web;

import com.example.shop.model.Product;
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

    public ReactiveController(ReactiveCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public Flux<Product> stream() {
        return catalogService.streamProducts();
    }

    @GetMapping("/{id}")
    public Mono<Product> one(@PathVariable Long id) {
        return catalogService.findProduct(id);
    }
}

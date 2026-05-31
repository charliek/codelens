package com.example.shop.web;

import com.example.shop.model.Product;
import com.example.shop.service.ReactiveCatalogService;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Handler component for the WebFlux *functional* endpoints (WebFlux.fn). Each
 * method has the shape {@code Mono<ServerResponse> handle(ServerRequest)} and is
 * wired to a route in {@link CatalogRouter}. Unlike annotated controllers, these
 * carry no request-mapping annotations — the routes live in the router bean.
 */
@Component
public class CatalogHandler {

    private final ReactiveCatalogService catalogService;

    public CatalogHandler(ReactiveCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        final long id;
        try {
            id = Long.parseLong(request.pathVariable("id"));
        } catch (NumberFormatException ex) {
            return ServerResponse.badRequest().bodyValue("Invalid catalog id");
        }
        return catalogService.findProduct(id)
            .flatMap(product -> ServerResponse.ok().bodyValue(product))
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> list(ServerRequest request) {
        return ServerResponse.ok().body(catalogService.streamProducts(), Product.class);
    }
}

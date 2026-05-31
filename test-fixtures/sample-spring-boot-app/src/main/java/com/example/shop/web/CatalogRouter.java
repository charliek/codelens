package com.example.shop.web;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * WebFlux *functional* routing: a {@code @Bean} returning a
 * {@link RouterFunction}. These routes carry no annotations, so they are
 * recovered by running
 * {@code codelens calls com.example.shop.web.CatalogRouter --method catalogRoutes}
 * and pairing each {@code RequestPredicates.GET("/path")} (the path is the
 * predicate's constant arg) with the adjacent {@code handler::method} reference
 * (an invokeDynamic whose implMethodName names the handler) — the same technique
 * the Ratpack skill uses for chains. (The reactive package
 * {@code org.springframework.web.reactive.function.server} distinguishes this
 * from MVC.fn under {@code org.springframework.web.servlet.function}.)
 *
 * <p>Note: the fluent builder form {@code route().GET("/p", predicate, handler)}
 * does not expose the path as a constant (the path LDC is not adjacent to the
 * routing call); for that style, read the source.
 */
@Configuration
public class CatalogRouter {

    @Bean
    public RouterFunction<ServerResponse> catalogRoutes(CatalogHandler handler) {
        return RouterFunctions
            .route(GET("/fn/catalog/{id}"), handler::get)
            .andRoute(GET("/fn/catalog"), handler::list);
    }
}

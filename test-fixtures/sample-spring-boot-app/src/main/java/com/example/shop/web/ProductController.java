package com.example.shop.web;

import com.example.shop.dto.ProductDto;
import com.example.shop.model.Product;
import com.example.shop.service.ProductService;
import com.example.shop.web.mapper.ProductMapper;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for products. */
@RestController
@RequestMapping("/products")
public class ProductController extends BaseController {

    private final ProductService productService;
    private final ProductMapper productMapper;

    public ProductController(ProductService productService, ProductMapper productMapper) {
        this.productService = productService;
        this.productMapper = productMapper;
    }

    @GetMapping
    public List<Product> list() {
        return productService.findAll();
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable Long id) {
        return productService.findById(id);
    }

    @PostMapping
    public Product create(@Valid @RequestBody ProductDto dto) {
        return productService.create(dto.getName(), dto.getPrice());
    }

    /** Admin-only import; uses the MapStruct mapper for the DTO->entity step. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/import")
    public Product importProduct(@Valid @RequestBody ProductDto dto) {
        Product entity = productMapper.toEntity(dto);
        return productService.create(entity.getName(), entity.getPrice());
    }
}

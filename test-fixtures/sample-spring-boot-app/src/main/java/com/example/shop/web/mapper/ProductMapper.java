package com.example.shop.web.mapper;

import com.example.shop.dto.ProductDto;
import com.example.shop.model.Product;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for the DTO&lt;-&gt;entity boundary. {@code componentModel = "spring"}
 * makes the generated {@code ProductMapperImpl} a {@code @Component}, so it shows
 * up in the bean graph and in {@code classes implementations
 * com.example.shop.web.mapper.ProductMapper}. The conversion methods are how an
 * agent finds which fields cross the request/persistence boundary.
 */
@Mapper(componentModel = "spring")
public interface ProductMapper {

    ProductDto toDto(Product product);

    Product toEntity(ProductDto dto);
}

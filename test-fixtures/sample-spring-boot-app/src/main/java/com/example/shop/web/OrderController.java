package com.example.shop.web;

import com.example.shop.dto.OrderDto;
import com.example.shop.model.Order;
import com.example.shop.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for orders. */
@RestController
@RequestMapping("/orders")
public class OrderController extends BaseController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/count")
    public long count() {
        return orderService.count();
    }

    @PostMapping
    public Order place(@RequestBody OrderDto dto) {
        return orderService.placeOrder(dto.getCustomerId(), dto.getTotal());
    }
}

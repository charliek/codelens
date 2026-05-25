package com.example.shop.report

import com.example.shop.service.NotificationService
import com.example.shop.service.OrderService
import com.example.shop.service.ProductService
import org.springframework.stereotype.Service

/**
 * The fixture's one Kotlin class, so both Java and Kotlin bytecode dialects are
 * scanned. Constructor-injected, and calls into several services (adding to the
 * dependency graph / foundation in-degrees).
 */
@Service
class ReportGenerator(
    private val productService: ProductService,
    private val orderService: OrderService,
    private val notificationService: NotificationService,
) {
    fun generateSummary(): String {
        val productCount = productService.findAll().size
        val orderCount = orderService.count()
        notificationService.notify("reports", "summary generated")
        return "products=$productCount orders=$orderCount"
    }
}

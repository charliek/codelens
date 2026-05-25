package com.example.shop.service;

import org.springframework.stereotype.Service;

/**
 * Notification helper injected into several services, so it shows up as a
 * high-in-degree "foundation" class in the dependency graph.
 */
@Service
public class NotificationService {

    public void notify(String channel, String message) {
        // no-op for the fixture
    }
}

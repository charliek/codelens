package com.example.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration bound from {@code shop.*} keys in
 * application.properties. Surface binding sites with
 * {@code codelens annotations usages
 * org.springframework.boot.context.properties.ConfigurationProperties}; the
 * effective values live in the (non-bytecode) properties file, which an agent
 * reads directly.
 */
@ConfigurationProperties(prefix = "shop")
public class AppProperties {

    private String catalogUrl = "https://catalog.example.com";
    private int pageSize = 25;

    public String getCatalogUrl() {
        return catalogUrl;
    }

    public void setCatalogUrl(String catalogUrl) {
        this.catalogUrl = catalogUrl;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}

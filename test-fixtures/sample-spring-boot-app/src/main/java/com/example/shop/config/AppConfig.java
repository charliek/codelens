package com.example.shop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

/** Application beans. The @Bean method passes a constant URL to a builder. */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    /** Direct property injection — the binding site for the shop.page-size key. */
    @Value("${shop.page-size:25}")
    private int defaultPageSize;

    @Bean
    public RestTemplate catalogRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setUriTemplateHandler(new DefaultUriBuilderFactory("https://catalog.example.com"));
        return restTemplate;
    }
}

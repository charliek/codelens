package com.example.shop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

/** Application beans. The @Bean method passes a constant URL to a builder. */
@Configuration
public class AppConfig {

    @Bean
    public RestTemplate catalogRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setUriTemplateHandler(new DefaultUriBuilderFactory("https://catalog.example.com"));
        return restTemplate;
    }
}

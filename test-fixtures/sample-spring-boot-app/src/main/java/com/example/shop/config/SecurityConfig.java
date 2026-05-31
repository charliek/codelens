package com.example.shop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration. The {@code SecurityFilterChain} bean encodes the
 * URL authorization rules inside a builder DSL (no annotations), so the
 * "endpoints x auth posture" recipe recovers them with
 * {@code codelens calls com.example.shop.config.SecurityConfig --method filterChain}
 * and reads the requestMatchers/permitAll/authenticated calls and their path
 * string constants. Method-level rules come from {@code @PreAuthorize}
 * (enabled by {@code @EnableMethodSecurity}).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/products/**").permitAll()
                .requestMatchers("/catalog/**").permitAll()
                .requestMatchers("/fn/catalog/**").permitAll()
                .requestMatchers("/orders/**").authenticated()
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}

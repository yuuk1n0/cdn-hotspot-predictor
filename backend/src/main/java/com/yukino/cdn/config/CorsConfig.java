package com.yukino.cdn.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origin}") String allowedOrigin) {
        this.allowedOrigins = Arrays.stream(allowedOrigin.split(","))
                .map(String::trim).filter(origin -> !origin.isBlank()).toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}

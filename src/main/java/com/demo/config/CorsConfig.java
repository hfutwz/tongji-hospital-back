package com.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * CORS跨域配置
 * 从 application.yml 中读取 cors.allowed-origins 配置
 */
@Configuration
@ConfigurationProperties(prefix = "cors")
public class CorsConfig implements WebMvcConfigurer {
    
    /**
     * 允许的跨域来源列表
     * 从配置文件中读取，支持多个环境
     */
    private List<String> allowedOrigins = new ArrayList<>();
    
    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }
    
    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
    
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 将List转换为数组
        String[] origins = allowedOrigins.toArray(new String[0]);
        
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)  // 允许携带凭证（cookie/session），用于登录验证
                .maxAge(3600);
    }
}


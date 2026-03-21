package com.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 预测微服务客户端配置
 * 提供 RestTemplate Bean，供 PredictionController 调用 Python 预测服务
 */
@Configuration
public class PredictionServiceConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}

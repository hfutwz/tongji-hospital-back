package com.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 预测微服务客户端配置
 * 提供 RestTemplate Bean，供 PredictionController 调用 Python 预测服务
 *
 * 超时设置：
 * - 连接超时 5s：Python 服务不可达时快速失败
 * - 读取超时 120s：模型训练（首次 Excel 训练）可能耗时较长
 */
@Configuration
public class PredictionServiceConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);    // 5秒连接超时
        factory.setReadTimeout(120_000);     // 120秒读取超时（训练可能耗时）
        return new RestTemplate(factory);
    }
}

package com.demo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan("com.demo.mapper")
@EnableAsync
public class HealthineersVisualizationApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealthineersVisualizationApplication.class, args);
    }

}

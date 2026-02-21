package com.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 文件上传配置类
 * 从 application.yml 中读取 file.* 配置项
 */
@Configuration
@ConfigurationProperties(prefix = "file")
public class FileUploadConfig {
    
    /**
     * 文件上传相对目录
     */
    private String uploadDir;
    
    /**
     * 文件上传绝对路径（可选）
     * 如果为空，则使用相对路径
     */
    private String uploadAbsolutePath;
    
    // Getters and Setters
    public String getUploadDir() {
        return uploadDir;
    }
    
    public void setUploadDir(String uploadDir) {
        this.uploadDir = uploadDir;
    }
    
    public String getUploadAbsolutePath() {
        return uploadAbsolutePath;
    }
    
    public void setUploadAbsolutePath(String uploadAbsolutePath) {
        this.uploadAbsolutePath = uploadAbsolutePath;
    }
    
    /**
     * 获取文件上传的根路径
     * 如果配置了绝对路径，使用绝对路径；否则使用相对路径
     */
    public String getUploadRootPath() {
        return uploadAbsolutePath != null && !uploadAbsolutePath.isEmpty() 
            ? uploadAbsolutePath 
            : uploadDir;
    }
}


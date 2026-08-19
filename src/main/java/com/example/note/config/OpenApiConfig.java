package com.example.note.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger/OpenAPI 文档信息（三项目同款，访问 /swagger-ui.html）
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("note-server API")
                .description("「晒晒」内容社区 —— 学习项目 #3：MQ 异步 / 削峰 / 刷数据 / Feed 流")
                .version("0.0.1"));
    }
}

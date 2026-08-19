package com.example.note.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置绑定 ≈ NestJS 的 registerAs('jwt') + ConfigService.get('jwt.secret')
 * application.yml 里 jwt.secret 等属性自动映射到字段
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String secret;

    private long accessTtlMinutes;

    private long refreshTtlDays;
}

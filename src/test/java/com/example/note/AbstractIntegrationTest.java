package com.example.note;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * note 集成测试基类：连 note 专属 MySQL(3309)/Redis(6381)，独立测试库 note_server_test
 * （三项目同套路：createDatabaseIfNotExist 自动建库，Testcontainers 等 Docker Desktop 兼容后可切）
 *
 * 随机端口起真实服务：测试走真 HTTP、穿完整 Security 过滤器链 —— 比 MockMvc 更接近线上
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3309/note_server_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.datasource.username=root",
        "spring.datasource.password=root123456",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6381"
})
public abstract class AbstractIntegrationTest {

    @LocalServerPort
    protected int port;
}

package com.example.note;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 端到端：注册 → 登录 → 发笔记（干净/敏感词/带图三种）→ 发现页 → 详情
 *
 * ⚠️ mall 踩坑结论沿用：测试类用 @Autowired 字段注入（构造注入会被 JUnit 当参数解析报错）
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NoteFlowE2ETest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate http;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @Order(1)
    void register_and_login_flow() {
        String username = "u_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> reg = Map.of("username", username, "password", "pass123456", "nickname", "测试用户");
        ResponseEntity<Map> r1 = http.postForEntity(url("/api/auth/register"), json(reg).plain(), Map.class);
        assertThat(r1.getStatusCode().value()).isEqualTo(200);
        assertThat(bodyCode(r1)).isEqualTo(0);

        // 重复注册 → 撞 uk_username → 409（GlobalExceptionHandler 的 DuplicateKey 翻译）
        ResponseEntity<Map> dup = http.postForEntity(url("/api/auth/register"), json(reg).plain(), Map.class);
        assertThat(dup.getStatusCode().value()).isEqualTo(409);

        // 登录拿 token
        ResponseEntity<Map> r2 = http.postForEntity(url("/api/auth/login"),
                json(Map.of("username", username, "password", "pass123456")).plain(), Map.class);
        assertThat(bodyCode(r2)).isEqualTo(0);
        assertThat(token(r2)).isNotBlank();

        // 错误密码 → 40101
        ResponseEntity<Map> bad = http.postForEntity(url("/api/auth/login"),
                json(Map.of("username", username, "password", "wrong-password")).plain(), Map.class);
        assertThat(bodyCode(bad)).isEqualTo(40101);
    }

    @Test
    @Order(2)
    void publish_clean_note_becomes_published() {
        String token = registerAndLogin();

        // 纯文字笔记：审核通过 → status=2
        Map<String, Object> note = Map.of("title", "周末露营清单", "content", "帐篷、天幕、卡式炉，出发！");
        ResponseEntity<Map> r = http.postForEntity(url("/api/notes"), json(note).withAuth(token), Map.class);
        assertThat(bodyCode(r)).isEqualTo(0);
        assertThat(data(r).get("status")).isEqualTo(2);

        Long noteId = ((Number) data(r).get("id")).longValue();

        // 发现页能看到它（Jackson 把 JSON 数字反序列化成 Integer/Long 不定，统一按 Number 取 longValue 再比）
        ResponseEntity<Map> latest = http.getForEntity(url("/api/notes/latest"), Map.class);
        List<?> records = (List<?>) data(latest).get("records");
        assertThat(records).extracting(n -> ((Number) ((Map<?, ?>) n).get("id")).longValue())
                .contains(noteId);
    }

    @Test
    @Order(3)
    void publish_sensitive_note_becomes_rejected() {
        String token = registerAndLogin();

        Map<String, Object> note = Map.of("title", "广告", "content", "加我代开发票");
        ResponseEntity<Map> r = http.postForEntity(url("/api/notes"), json(note).withAuth(token), Map.class);
        assertThat(bodyCode(r)).isEqualTo(0);
        // 敏感词 → 驳回 status=3，且不出现在发现页
        assertThat(data(r).get("status")).isEqualTo(3);

        Long noteId = ((Number) data(r).get("id")).longValue();
        ResponseEntity<Map> latest = http.getForEntity(url("/api/notes/latest"), Map.class);
        List<?> records = (List<?>) data(latest).get("records");
        assertThat(records).extracting(n -> ((Number) ((Map<?, ?>) n).get("id")).longValue())
                .doesNotContain(noteId);
    }

    /**
     * 带图发布：给 Phase 1 留的「病灶证据」——
     * 同步版每张图 sleep 200ms，3 张图 = 接口至少 600ms。
     * Phase 1 改造完 MQ 后，同样的断言会变成「100ms 内返回」——前后对比就是异步化的价值
     */
    @Test
    @Order(4)
    void publish_with_images_is_slow_on_purpose() {
        String token = registerAndLogin();

        Map<String, Object> note = Map.of(
                "title", "九宫格之第一弹",
                "content", "三张图测试",
                "images", List.of("https://cdn.shai.ai/a.jpg", "https://cdn.shai.ai/b.jpg", "https://cdn.shai.ai/c.jpg"));
        long start = System.currentTimeMillis();
        ResponseEntity<Map> r = http.postForEntity(url("/api/notes"), json(note).withAuth(token), Map.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(bodyCode(r)).isEqualTo(0);
        assertThat(elapsed).as("同步版病灶证据：3 张图至少耗 600ms").isGreaterThanOrEqualTo(600);

        // 图片 URL 被改写成压缩版（?w=800&fmt=webp）且带图序入库
        Long noteId = ((Number) data(r).get("id")).longValue();
        ResponseEntity<Map> detail = http.getForEntity(url("/api/notes/" + noteId), Map.class);
        List<?> images = (List<?>) data(detail).get("images");
        assertThat(images).hasSize(3);
        assertThat(images.get(0).toString()).contains("w=800");
    }

    @Test
    @Order(5)
    void publish_without_login_is_401() {
        Map<String, Object> note = Map.of("title", "匿名发布", "content", "不行哦");
        ResponseEntity<Map> r = http.postForEntity(url("/api/notes"), json(note).plain(), Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(401);
    }

    // ---------- 小工具 ----------

    private int bodyCode(ResponseEntity<Map> r) {
        return (Integer) r.getBody().get("code");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> r) {
        return (Map<String, Object>) r.getBody().get("data");
    }

    private String token(ResponseEntity<Map> r) {
        return (String) data(r).get("accessToken");
    }

    private String registerAndLogin() {
        String username = "u_" + UUID.randomUUID().toString().substring(0, 8);
        http.postForEntity(url("/api/auth/register"),
                json(Map.of("username", username, "password", "pass123456", "nickname", "作者")).plain(), Map.class);
        ResponseEntity<Map> login = http.postForEntity(url("/api/auth/login"),
                json(Map.of("username", username, "password", "pass123456")).plain(), Map.class);
        return token(login);
    }

    /** 包装 JSON 请求体；withAuth() 链式加 Bearer 头 —— 测试里少写四行模板代码 */
    private JsonBody json(Map<String, Object> body) {
        return new JsonBody(body);
    }

    private record JsonBody(Map<String, Object> body) {
        HttpEntity<Map<String, Object>> withAuth(String token) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);
            return new HttpEntity<>(body, headers);
        }

        HttpEntity<Map<String, Object>> plain() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new HttpEntity<>(body, headers);
        }
    }
}

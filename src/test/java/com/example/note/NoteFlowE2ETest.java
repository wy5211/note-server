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

        // 纯文字笔记：接口秒回，返回时 status=1（审核中）—— Phase 1 异步化的直接证据
        Map<String, Object> note = Map.of("title", "周末露营清单", "content", "帐篷、天幕、卡式炉，出发！");
        ResponseEntity<Map> r = http.postForEntity(url("/api/notes"), json(note).withAuth(token), Map.class);
        assertThat(bodyCode(r)).isEqualTo(0);
        assertThat(data(r).get("status")).isEqualTo(1);

        Long noteId = ((Number) data(r).get("id")).longValue();

        // 最终一致：审核消费者稍后流转到 status=2（Phase 0 版本是同步直接断言 2）
        // ⚠️ 超时给到 40s 的原因：全新环境首次跑测试时踩「消费者先于 topic 就绪」的经典时序坑 ——
        //    autoCreateTopicEnable 模式下 topic 由 producer 第一条消息创建，消费者启动时还没这个 topic 的
        //    路由，要等 30s 路由刷新周期。topic 一旦创建会持久存在，之后每次运行都是秒级。
        //    （生产环境的正解：topic 提前建好 + autoCreateTopicEnable=false，见 broker.conf 注释）
        Map<String, Object> done = awaitNoteStatus(noteId, 2, 40_000);
        assertThat(done).as("审核消费者应在 40s 内流转为已发布（含首次 topic 冷启动）").isNotNull();

        // 发现页能看到它（Jackson 把 JSON 数字反序列化成 Integer/Long 不定，统一按 Number 取 longValue 再比）
        ResponseEntity<Map> latest = http.getForEntity(url("/api/notes/latest?size=50"), Map.class);
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
        assertThat(data(r).get("status")).isEqualTo(1);   // 秒回时都是审核中，稍后才驳回

        Long noteId = ((Number) data(r).get("id")).longValue();
        Map<String, Object> done = awaitNoteStatus(noteId, 3, 40_000);
        assertThat(done).as("审核消费者应驳回敏感笔记（含首次 topic 冷启动余量）").isNotNull();

        // 驳回笔记不出现在发现页
        ResponseEntity<Map> latest = http.getForEntity(url("/api/notes/latest?size=50"), Map.class);
        List<?> records = (List<?>) data(latest).get("records");
        assertThat(records).extracting(n -> ((Number) ((Map<?, ?>) n).get("id")).longValue())
                .doesNotContain(noteId);
    }

    /**
     * 前后对比的活教材（Phase 0 版本断言「3 张图至少 600ms」）：
     * 异步化后：接口快返回（原图直接入库），压缩改写由图片消费者后台完成
     */
    @Test
    @Order(4)
    void publish_with_images_returns_fast_and_images_process_async() {
        String token = registerAndLogin();

        Map<String, Object> note = Map.of(
                "title", "九宫格之第一弹",
                "content", "三张图测试",
                "images", List.of("https://cdn.shai.ai/a.jpg", "https://cdn.shai.ai/b.jpg", "https://cdn.shai.ai/c.jpg"));
        long start = System.currentTimeMillis();
        ResponseEntity<Map> r = http.postForEntity(url("/api/notes"), json(note).withAuth(token), Map.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(bodyCode(r)).isEqualTo(0);
        // Phase 0 同场景断言：elapsed >= 600ms。现在：快返回（留出两次 insert 的余量）
        assertThat(elapsed).as("异步版发布应快返回").isLessThan(500);

        Long noteId = ((Number) data(r).get("id")).longValue();
        assertThat(awaitNoteStatus(noteId, 2, 10_000)).isNotNull();
        assertThat(awaitImageProcessed(noteId, 10_000)).isTrue();
    }

    @Test
    @Order(5)
    void publish_without_login_is_401() {
        Map<String, Object> note = Map.of("title", "匿名发布", "content", "不行哦");
        ResponseEntity<Map> r = http.postForEntity(url("/api/notes"), json(note).plain(), Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(401);
    }

    // ---------- 小工具 ----------

    /**
     * 轮询等笔记到达目标状态（异步化的测试标配：不再能同步断言结果，就轮询等「最终一致」）。
     * @return 到达时的详情 data；超时返回 null（交给调用方断言失败信息）
     */
    private Map<String, Object> awaitNoteStatus(Long noteId, int expectedStatus, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<Map> r = http.getForEntity(url("/api/notes/" + noteId), Map.class);
            if (r.getStatusCode().is2xxSuccessful() && data(r) != null
                    && data(r).get("status") instanceof Number n && n.intValue() == expectedStatus) {
                return data(r);
            }
            sleep(200);
        }
        return null;
    }

    /** 轮询等图片消费者完成压缩改写（所有图都带 w=800 参数） */
    private boolean awaitImageProcessed(Long noteId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<Map> r = http.getForEntity(url("/api/notes/" + noteId), Map.class);
            List<?> images = r.getStatusCode().is2xxSuccessful() && data(r) != null
                    ? (List<?>) data(r).get("images") : List.of();
            if (!images.isEmpty() && images.stream().allMatch(i -> i.toString().contains("w=800"))) {
                return true;
            }
            sleep(200);
        }
        return false;
    }

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

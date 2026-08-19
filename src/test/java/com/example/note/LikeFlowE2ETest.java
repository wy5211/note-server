package com.example.note;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.like.DbLikeService;
import com.example.note.like.RedisLikeService;
import com.example.note.like.entity.NoteLike;
import com.example.note.like.mapper.NoteLikeMapper;
import com.example.note.note.entity.Note;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 功能等价性验收：三版点赞对外行为一致（点赞生效 / 重复幂等 / 取消生效）
 *
 * V1/V2 用注入直调（各自核心路径），V3 走完整 HTTP E2E（Redis 秒回 + MQ 攒批落库的最终一致）。
 * 性能对比不在这里 —— 那是 LikeLoadTest 压测课的活
 */
class LikeFlowE2ETest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private DbLikeService dbLikeService;
    @Autowired
    private RedisLikeService redisLikeService;
    @Autowired
    private NoteLikeMapper noteLikeMapper;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ---------- V1 直写库 ----------

    @Test
    void v1_db_like_is_persistent_and_idempotent() {
        Long noteId = insertNote("V1 压测靶子", "直写库版", Note.STATUS_PUBLISHED);

        dbLikeService.like(1001L, noteId);
        dbLikeService.like(1001L, noteId);      // 重复点赞：INSERT IGNORE 挡住

        Note note = noteMapper.selectById(noteId);
        assertThat(note.getLikeCount()).as("重复点赞只计一次（uk_user_note + affected 判断）").isEqualTo(1);
        assertThat(dbLikeService.liked(1001L, noteId)).isTrue();

        dbLikeService.unlike(1001L, noteId);
        dbLikeService.unlike(1001L, noteId);    // 重复取消：DELETE 0 行
        assertThat(noteMapper.selectById(noteId).getLikeCount()).isEqualTo(0);
        assertThat(dbLikeService.liked(1001L, noteId)).isFalse();
    }

    // ---------- V2 Redis ----------

    @Test
    void v2_redis_like_is_fast_and_idempotent() {
        Long noteId = insertNote("V2 靶子", "Redis 版", Note.STATUS_PUBLISHED);

        redisLikeService.like(2001L, noteId);
        redisLikeService.like(2001L, noteId);   // 位图已置位 → 幂等返回

        assertThat(redisLikeService.readCount(noteId)).isEqualTo(1);
        assertThat(redisLikeService.liked(2001L, noteId)).isTrue();

        redisLikeService.unlike(2001L, noteId);
        redisLikeService.unlike(2001L, noteId);
        assertThat(redisLikeService.readCount(noteId)).as("取消也要幂等且不减穿").isEqualTo(0);
        assertThat(redisLikeService.liked(2001L, noteId)).isFalse();

        // 库里没有痕迹 —— V2 的已知代价（Phase 3 刷数据的钩子）
        assertThat(countInDb(noteId)).isEqualTo(0);
        assertThat(noteMapper.selectById(noteId).getLikeCount()).isEqualTo(0);
    }

    // ---------- V3 最终形态（HTTP E2E）----------

    @Test
    void v3_mq_like_returns_fast_and_eventually_persisted() {
        Long noteId = insertNote("V3 靶子", "MQ 攒批版", Note.STATUS_PUBLISHED);
        String token = registerAndLogin();
        Long userId = userIdOf(token);

        // 同步半场：接口返回后 Redis 立刻可见（用户看到的数字是秒更新的）
        post(token, "/api/notes/" + noteId + "/like");
        Map<String, Object> status = getLikeStatus(token, noteId);
        assertThat(status.get("liked")).isEqualTo(true);
        assertThat(((Number) status.get("count")).longValue()).isEqualTo(1);

        // 异步半场：MQ → 攒批 → 2s 定时 flush → 库里出现关系行和计数（最终一致）
        assertThat(awaitDbLike(noteId, 1, 15_000))
                .as("攒批落库应在 15s 内完成（flush 间隔 2s + 消费延迟）").isTrue();

        // 重复点赞（HTTP 层）：Redis 幂等 + MQ 消息重复投递也不重复计数
        post(token, "/api/notes/" + noteId + "/like");
        sleep(4_000);   // 等 flush 周期跑过
        assertThat(noteMapper.selectById(noteId).getLikeCount())
                .as("重复消息被 INSERT IGNORE 挡住，计数不变").isEqualTo(1);
    }

    /** 游客查点赞状态：liked=false 但 count 照常返回（GET 放行的验证） */
    @Test
    void guest_can_read_like_status() {
        Long noteId = insertNote("游客靶子", "匿名可读", Note.STATUS_PUBLISHED);
        Map<String, Object> status = getLikeStatus(null, noteId);
        assertThat(status.get("liked")).isEqualTo(false);
        assertThat(((Number) status.get("count")).longValue()).isEqualTo(0);
    }

    // ---------- 小工具 ----------

    private long countInDb(Long noteId) {
        return noteLikeMapper.selectCount(Wrappers.<NoteLike>lambdaQuery()
                .eq(NoteLike::getNoteId, noteId));
    }

    private boolean awaitDbLike(Long noteId, long expectedCount, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (countInDb(noteId) == expectedCount
                    && noteMapper.selectById(noteId).getLikeCount() == expectedCount) {
                return true;
            }
            sleep(300);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getLikeStatus(String token, Long noteId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        org.springframework.http.ResponseEntity<Map> r =
                http.exchange(url("/api/notes/" + noteId + "/like"),
                        org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        return (Map<String, Object>) r.getBody().get("data");
    }

    private void post(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        http.postForEntity(url(path), new HttpEntity<>(Map.of(), headers), Map.class);
    }

    /** 从 access token 解 userId —— 测试里省一次 /me 调用（JWT 的 sub 就是 userId） */
    private Long userIdOf(String token) {
        String payload = token.split("\\.")[1];
        String json = new String(java.util.Base64.getUrlDecoder().decode(payload));
        return Long.parseLong(json.replaceAll(".*\"sub\":\"(\\d+)\".*", "$1"));
    }

    private String registerAndLogin() {
        String username = "u_" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        http.postForEntity(url("/api/auth/register"),
                new HttpEntity<>(Map.of("username", username, "password", "pass123456", "nickname", "点赞人"), jsonHeaders), Map.class);
        org.springframework.http.ResponseEntity<Map> login = http.postForEntity(url("/api/auth/login"),
                new HttpEntity<>(Map.of("username", username, "password", "pass123456"), jsonHeaders), Map.class);
        return (String) ((Map<?, ?>) login.getBody().get("data")).get("accessToken");
    }
}

package com.example.note;

import com.example.note.follow.mapper.FollowMapper;
import com.example.note.note.entity.Note;
import com.example.note.ranking.RankingJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 验收：Feed 推/拉两路 + 游标翻页 + 热榜算分 + HyperLogLog 阅读量
 */
class FeedE2ETest extends AbstractIntegrationTest {

    /** 造大 V 用的假粉丝数（>1000 阈值即判定为大 V，走拉模式） */
    private static final int BIG_V_FANS = 1001;

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private RankingJob rankingJob;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ---------- 推模式（素人写扩散）----------

    @Test
    void plain_author_note_reaches_follower_inbox_via_push() {
        String authorToken = registerAndLogin();
        Long authorId = userIdOf(authorToken);
        String fanToken = registerAndLogin();
        Long fanId = userIdOf(fanToken);

        // 先关注，再发布（收件箱只收关注之后的新笔记 —— 微博同款行为）
        post(fanToken, "/api/users/" + authorId + "/follow");

        Long noteId = publishNote(authorToken, "推模式笔记", "素人发布，应该进粉丝收件箱");
        assertThat(awaitFeedContains(fanToken, noteId, 15_000))
                .as("MQ 写扩散后，粉丝 Feed 应出现该笔记").isTrue();
    }

    @Test
    void feed_pagination_uses_cursor_without_overlap() {
        String authorToken = registerAndLogin();
        Long authorId = userIdOf(authorToken);
        String fanToken = registerAndLogin();

        post(fanToken, "/api/users/" + authorId + "/follow");
        Long n1 = publishNote(authorToken, "翻页1", "c");
        Long n2 = publishNote(authorToken, "翻页2", "c");
        Long n3 = publishNote(authorToken, "翻页3", "c");
        assertThat(awaitFeedContains(fanToken, n1, 15_000)).isTrue();   // 等最老的也进收件箱

        // 第一页 size=2：应为最新的两篇（n3、n2），带回 nextCursor
        Map<String, Object> page1 = getFeed(fanToken, null, 2);
        List<Long> ids1 = feedNoteIds(page1);
        assertThat(ids1).containsExactly(n3.longValue(), n2.longValue());

        Long cursor = ((Number) ((Map<?, ?>) page1.get("data")).get("nextCursor")).longValue();
        assertThat(cursor).isEqualTo(n2);

        // 第二页从 cursor 继续：只有 n1，nextCursor 为 null（没有更多）
        Map<String, Object> page2 = getFeed(fanToken, cursor, 2);
        assertThat(feedNoteIds(page2)).containsExactly(n1.longValue());
        assertThat(nextCursor(page2)).isNull();
    }

    // ---------- 拉模式（大 V 不写扩散，读时现查）----------

    @Test
    void big_v_note_served_by_pull_not_push() {
        String bigVToken = registerAndLogin();
        Long bigVId = userIdOf(bigVToken);
        String fanToken = registerAndLogin();

        // 大 V 直插一篇已发布笔记（绕开发布链路：大 V 场景 push 本来就会跳过，不需要真发 MQ）
        Long noteId = insertBigVNote(bigVId, "大V爆款", "十万人在看");

        // 造 1001 个粉丝 → 超过推送阈值 → 判定为大 V
        List<Long> fans = LongStream.rangeClosed(4_500_000L, 4_500_000L + BIG_V_FANS - 1).boxed().toList();
        followMapper.insertFans(fans, bigVId);

        // 真粉丝关注大 V，feed 里靠拉模式看到大 V 笔记
        post(fanToken, "/api/users/" + bigVId + "/follow");
        Map<String, Object> feed = getFeed(fanToken, null, 20);
        assertThat(feedNoteIds(feed))
                .as("大 V 笔记不进收件箱，但拉模式应在 Feed 里看到").contains(noteId.longValue());
    }

    // ---------- 热榜 ----------

    @Test
    void hot_ranking_orders_by_engagement_and_recency() {
        // 三篇笔记：高互动新 / 低互动新 / 高互动旧 —— 期望顺序：高互动新 > 高互动旧 > 低互动新
        // 互动量级要压过历史测试数据（压测遗留 ~百赞笔记）—— 排序断言才不会被污染
        Long hotNew = insertNote("高互动新帖", "c", Note.STATUS_PUBLISHED);
        Long hotOld = insertNote("高互动旧帖", "c", Note.STATUS_PUBLISHED);
        Long coldNew = insertNote("低互动新帖", "c", Note.STATUS_PUBLISHED);

        // 分数心算（热榜公式的必考点）：
        //   hotNew  = 50000×2 / (0+2)^1.5  = 100000/2.83  ≈ 35355
        //   hotOld  = 100000×2 / (72+2)^1.5 = 200000/636.8 ≈ 314
        //   coldNew = 300×2 / (0+2)^1.5   = 600/2.83     ≈ 212
        // 期望顺序 hotNew > hotOld > coldNew（时间衰减让 10 万赞的 3 天旧帖险胜 300 赞新帖）
        updateLikeCountAndCreatedAt(hotNew, 50_000, java.time.LocalDateTime.now());
        updateLikeCountAndCreatedAt(hotOld, 100_000, java.time.LocalDateTime.now().minusDays(3));
        updateLikeCountAndCreatedAt(coldNew, 300, java.time.LocalDateTime.now());

        rankingJob.computeOnce();

        List<Map<String, Object>> rank = getHotRanking(10);
        List<Long> order = rank.stream().map(i -> ((Number) i.get("noteId")).longValue()).toList();
        assertThat(order.indexOf(hotNew)).as("高互动新帖应排最前").isEqualTo(0);
        assertThat(order.indexOf(hotOld)).as("高互动旧帖应压过低互动新帖（互动占优于衰减）")
                .isLessThan(order.indexOf(coldNew));
    }

    // ---------- HyperLogLog 阅读量 ----------

    @Test
    void read_count_is_uv_not_pv() {
        Long noteId = insertNote("阅读量靶子", "同一人刷一百次只算一次", Note.STATUS_PUBLISHED);
        String tokenA = registerAndLogin();

        // 同一用户看两次：UV 仍为 1
        assertThat(readCountOf(tokenA, noteId)).isEqualTo(1);
        assertThat(readCountOf(tokenA, noteId)).isEqualTo(1);

        // 换个用户：UV = 2（PFADD 按标识去重）
        String tokenB = registerAndLogin();
        assertThat(readCountOf(tokenB, noteId)).isEqualTo(2);
    }

    // ---------- 小工具 ----------

    private Long publishNote(String token, String title, String content) {
        Map<String, Object> r = post(token, "/api/notes", Map.of("title", title, "content", content));
        return ((Number) ((Map<?, ?>) r.get("data")).get("id")).longValue();
    }

    private boolean awaitFeedContains(String token, Long noteId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (feedNoteIds(getFeed(token, null, 50)).contains(noteId)) {
                return true;
            }
            sleep(300);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Long> feedNoteIds(Map<String, Object> feedResp) {
        Map<String, Object> data = (Map<String, Object>) feedResp.get("data");
        List<Map<String, Object>> notes = (List<Map<String, Object>>) (List<?>) data.get("notes");
        return notes.stream().map(n -> ((Number) n.get("id")).longValue()).toList();
    }

    private Long nextCursor(Map<String, Object> feedResp) {
        Object c = ((Map<?, ?>) feedResp.get("data")).get("nextCursor");
        return c == null ? null : ((Number) c).longValue();
    }

    private Map<String, Object> getFeed(String token, Long cursor, int size) {
        String u = url("/api/feed?size=" + size + (cursor == null ? "" : "&cursor=" + cursor));
        org.springframework.http.ResponseEntity<Map> r =
                http.exchange(u, org.springframework.http.HttpMethod.GET,
                        new HttpEntity<>(auth(token)), Map.class);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getHotRanking(int top) {
        org.springframework.http.ResponseEntity<Map> r = http.getForEntity(
                url("/api/ranking/hot?top=" + top), Map.class);
        return (List<Map<String, Object>>) r.getBody().get("data");
    }

    private int readCountOf(String token, Long noteId) {
        org.springframework.http.ResponseEntity<Map> r = http.exchange(
                url("/api/notes/" + noteId), org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(auth(token)), Map.class);
        return ((Number) ((Map<?, ?>) r.getBody().get("data")).get("readCount")).intValue();
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private Map<String, Object> post(String token, String path) {
        return post(token, path, Map.of());
    }

    private Map<String, Object> post(String token, String path, Map<String, Object> body) {
        HttpHeaders h = auth(token);
        org.springframework.http.ResponseEntity<Map> r =
                http.postForEntity(url(path), new HttpEntity<>(body, h), Map.class);
        return r.getBody();
    }

    private String registerAndLogin() {
        String username = "u_" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        http.postForEntity(url("/api/auth/register"),
                new HttpEntity<>(Map.of("username", username, "password", "pass123456", "nickname", "feed用户"), jsonHeaders), Map.class);
        org.springframework.http.ResponseEntity<Map> login = http.postForEntity(url("/api/auth/login"),
                new HttpEntity<>(Map.of("username", username, "password", "pass123456"), jsonHeaders), Map.class);
        return (String) ((Map<?, ?>) login.getBody().get("data")).get("accessToken");
    }

    private Long userIdOf(String token) {
        String payload = token.split("\\.")[1];
        String json = new String(java.util.Base64.getUrlDecoder().decode(payload));
        return Long.parseLong(json.replaceAll(".*\"sub\":\"(\\d+)\".*", "$1"));
    }

    private Long insertBigVNote(Long bigVId, String title, String content) {
        Note note = new Note();
        note.setUserId(bigVId);
        note.setTitle(title);
        note.setContent(content);
        note.setStatus(Note.STATUS_PUBLISHED);
        note.setLikeCount(10);
        note.setCollectCount(0);
        note.setCommentCount(0);
        note.setReadCount(0);
        noteMapper.insert(note);
        return note.getId();
    }

    private void updateLikeCountAndCreatedAt(Long noteId, int likeCount, java.time.LocalDateTime createdAt) {
        Note n = noteMapper.selectById(noteId);
        n.setLikeCount(likeCount);
        n.setCreatedAt(createdAt);   // 显式 set 可覆盖（无 ON UPDATE 干扰 created_at）
        noteMapper.updateById(n);
    }
}

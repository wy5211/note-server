package com.example.note;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.comment.entity.Comment;
import com.example.note.comment.mapper.CommentMapper;
import com.example.note.localmsg.LocalMessageRelayJob;
import com.example.note.localmsg.entity.LocalMessage;
import com.example.note.localmsg.mapper.LocalMessageMapper;
import com.example.note.point.entity.PointLedger;
import com.example.note.point.mapper.PointLedgerMapper;
import com.example.note.user.entity.User;
import com.example.note.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 验收（方案一·本地消息表）：审核 → 本地消息 → 中继 → MQ → 积分，全链路最终一致
 */
class PointRewardE2ETest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private LocalMessageRelayJob relayJob;
    @Autowired
    private LocalMessageMapper localMessageMapper;
    @Autowired
    private PointLedgerMapper ledgerMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private CommentMapper commentMapper;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void clean_note_eventually_gets_reward_via_local_message_table() {
        var author = registerAndLogin();
        Long noteId = publish(author.token(), "本地消息表演练-" + author.userId(), "干净内容");

        // 链路：审核消费者（事务内 status→2 + local_message 落表）→ 中继 → MQ → 积分消费者
        assertThat(awaitReward(noteId, author.userId(), 20_000))
                .as("本地消息表链路应最终把奖励送达").isTrue();

        // 账目三件套：流水一条、金额 50、余额 50（balance_after 快照一致）
        PointLedger ledger = ledgerMapper.selectList(Wrappers.<PointLedger>lambdaQuery()
                .eq(PointLedger::getNoteId, noteId)).get(0);
        assertThat(ledger.getAmount()).isEqualTo(50);
        assertThat(ledger.getBalanceAfter()).isEqualTo(50);
        assertThat(userMapper.selectById(author.userId()).getPointTotal()).isEqualTo(50);

        // 消息表状态：该消息已被中继标记 SENT
        LocalMessage msg = localMessageMapper.selectOne(Wrappers.<LocalMessage>lambdaQuery()
                .eq(LocalMessage::getMessageKey, "reward:" + noteId));
        assertThat(msg.getStatus()).isEqualTo(LocalMessage.STATUS_SENT);
    }

    @Test
    void rejected_note_never_gets_reward() {
        var author = registerAndLogin();
        Long noteId = publish(author.token(), "驳回演练-" + author.userId(), "加我代开发票");

        assertThat(awaitNoteStatus(noteId, 3, 15_000)).isTrue();
        relayJob.relayOnce();   // 跑一轮中继（即使有漏网消息也不该有驳回的奖励）
        sleep(2_000);

        assertThat(ledgerMapper.selectCount(Wrappers.<PointLedger>lambdaQuery()
                .eq(PointLedger::getNoteId, noteId)))
                .as("驳回笔记不应有奖励流水").isZero();
        // 断言收窄到本测试的笔记（likeRight 全表会被其他测试的合法消息污染 —— 教训同踩坑 #14）
        assertThat(localMessageMapper.selectCount(Wrappers.<LocalMessage>lambdaQuery()
                .eq(LocalMessage::getMessageKey, "reward:" + noteId)))
                .as("驳回路径根本不会写本地消息").isZero();
    }

    /** 中继重复投递（消息重复消费）→ 消费端锁+流水幂等 → 积分不双发 */
    @Test
    void duplicate_delivery_does_not_double_reward() {
        var author = registerAndLogin();
        Long noteId = publish(author.token(), "幂等演练-" + author.userId(), "干净内容");
        assertThat(awaitReward(noteId, author.userId(), 20_000)).isTrue();

        // 模拟「中继重投」：把已发消息重置为 PENDING，再手动中继一次
        LocalMessage msg = localMessageMapper.selectOne(Wrappers.<LocalMessage>lambdaQuery()
                .eq(LocalMessage::getMessageKey, "reward:" + noteId));
        msg.setStatus(LocalMessage.STATUS_PENDING);
        msg.setRetryCount(0);
        localMessageMapper.updateById(msg);

        relayJob.relayOnce();
        sleep(3_000);   // 等消费完成

        assertThat(userMapper.selectById(author.userId()).getPointTotal())
                .as("重复消息被消费端幂等挡住，积分不双发").isEqualTo(50);
        assertThat(ledgerMapper.selectCount(Wrappers.<PointLedger>lambdaQuery()
                .eq(PointLedger::getNoteId, noteId))).isEqualTo(1);
    }

    /** 顺序消息：同笔记连发 3 条评论，楼层恰好 1/2/3 无跳号无重复 */
    @Test
    void comment_floors_are_sequential_per_note() {
        var author = registerAndLogin();
        Long noteId = insertNote("楼层靶子-" + author.userId(), "c", 2);

        for (int i = 1; i <= 3; i++) {
            postComment(author.token(), noteId, "第 " + i + " 楼预定");
        }

        assertThat(awaitFloors(noteId, 3, 15_000)).isTrue();
        Set<Integer> floors = commentMapper.selectList(Wrappers.<Comment>lambdaQuery()
                        .eq(Comment::getNoteId, noteId))
                .stream().map(Comment::getFloor).collect(Collectors.toSet());
        assertThat(floors).as("楼层应为 1/2/3").containsExactlyInAnyOrder(1, 2, 3);
    }

    // ---------- 小工具 ----------

    private boolean awaitReward(Long noteId, Long userId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            long ledgerCount = ledgerMapper.selectCount(Wrappers.<PointLedger>lambdaQuery()
                    .eq(PointLedger::getNoteId, noteId));
            if (ledgerCount > 0) {
                return true;   // 流水在，余额必然在（同事务）
            }
            relayJob.relayOnce();   // 驱动链路前进（测试环境自动中继已关）
            sleep(1_000);
        }
        return false;
    }

    private boolean awaitNoteStatus(Long noteId, int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (noteMapper.selectById(noteId) != null
                    && noteMapper.selectById(noteId).getStatus() == expected) {
                return true;
            }
            sleep(300);
        }
        return false;
    }

    private boolean awaitFloors(Long noteId, int expectedCount, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            long assigned = commentMapper.selectCount(Wrappers.<Comment>lambdaQuery()
                    .eq(Comment::getNoteId, noteId).isNotNull(Comment::getFloor));
            if (assigned >= expectedCount) {
                return true;
            }
            sleep(300);
        }
        return false;
    }

    private Long publish(String token, String title, String content) {
        HttpHeaders h = auth(token);
        var r = http.postForEntity(url("/api/notes"),
                new HttpEntity<>(Map.of("title", title, "content", content), h), Map.class);
        return ((Number) ((Map<?, ?>) r.getBody().get("data")).get("id")).longValue();
    }

    private void postComment(String token, Long noteId, String content) {
        http.postForEntity(url("/api/notes/" + noteId + "/comments"),
                new HttpEntity<>(Map.of("content", content), auth(token)), Map.class);
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private Author registerAndLogin() {
        String username = "u_" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        http.postForEntity(url("/api/auth/register"),
                new HttpEntity<>(Map.of("username", username, "password", "pass123456", "nickname", "作者"), json), Map.class);
        var login = http.postForEntity(url("/api/auth/login"),
                new HttpEntity<>(Map.of("username", username, "password", "pass123456"), json), Map.class);
        String token = (String) ((Map<?, ?>) login.getBody().get("data")).get("accessToken");
        long userId = ((Number) ((Map<?, ?>) login.getBody().get("data")).get("userId")).longValue();
        return new Author(token, userId);
    }

    private record Author(String token, long userId) {
    }
}

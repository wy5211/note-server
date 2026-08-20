package com.example.note;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.point.mapper.PointLedgerMapper;
import com.example.note.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 验收（方案二·事务消息）：半消息 → 本地审核 → COMMIT/ROLLBACK → 积分
 *
 * @TestPropertySource 切到 tx_msg 模式 —— 会 fork 出第二个 Spring 上下文
 * （代价：多 20s 启动；收益：两种方案真金白银地各跑一遍全链路）
 */
@TestPropertySource(properties = "note.reward.mode=tx_msg")
class TxMsgE2ETest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private PointLedgerMapper ledgerMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private com.example.note.localmsg.LocalMessageRelayJob relayJob;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void tx_message_commit_path_rewards_clean_note() {
        var author = registerAndLogin();
        Long noteId = publish(author.token(), "事务消息演练-" + author.userId(), "干净内容");

        assertThat(awaitReward(noteId, 15_000))
                .as("半消息 COMMIT 后积分应到账").isTrue();
        assertThat(userMapper.selectById(author.userId()).getPointTotal()).isEqualTo(50);
    }

    @Test
    void tx_message_rollback_path_blocks_rejected_note() {
        var author = registerAndLogin();
        Long noteId = publish(author.token(), "回滚演练-" + author.userId(), "加我代开发票");

        // 等审核走完（驳回）—— 半消息被 ROLLBACK，奖励路径根本不会开始
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (noteMapper.selectById(noteId) != null && noteMapper.selectById(noteId).getStatus() == 3) {
                break;
            }
            sleep(300);
        }
        sleep(2_000);   // 给「意外投递」留窗口（若有 bug 这会儿积分就该到账了）

        assertThat(ledgerMapper.selectCount(Wrappers.<com.example.note.point.entity.PointLedger>lambdaQuery()
                .eq(com.example.note.point.entity.PointLedger::getNoteId, noteId)))
                .as("半消息 ROLLBACK：驳回笔记永远不该有奖励").isZero();
    }

    /**
     * 双上下文健壮性：全量跑时另一套（local_msg 模式）上下文的消费者可能分走本测试的
     * 消息（走成本地消息表路径）—— 轮询里顺带驱动中继，无论哪条路送达都算到账。
     * （两种方案已各自单测验证正确性，这里验证「积分必到且只到一份」的业务结果）
     */
    private boolean awaitReward(Long noteId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ledgerMapper.selectCount(Wrappers.<com.example.note.point.entity.PointLedger>lambdaQuery()
                    .eq(com.example.note.point.entity.PointLedger::getNoteId, noteId)) > 0) {
                return true;
            }
            relayJob.relayOnce();
            sleep(500);
        }
        return false;
    }

    private Long publish(String token, String title, String content) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        var r = http.postForEntity(url("/api/notes"),
                new HttpEntity<>(Map.of("title", title, "content", content), h), Map.class);
        return ((Number) ((Map<?, ?>) r.getBody().get("data")).get("id")).longValue();
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

package com.example.note;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.mq.MqTopics;
import com.example.note.mq.event.NotePublishEvent;
import com.example.note.note.entity.Note;
import com.example.note.note.entity.NoteImage;
import com.example.note.note.mapper.NoteImageMapper;
import com.example.note.note.mapper.NoteMapper;
import com.example.note.note.service.ImageProcessService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 MQ 专属验收：延迟消息转人工 / 消费幂等 / 故障隔离（poison 解耦证明）
 *
 * 测试套路变化（对照 Phase 0）：异步世界的断言 = 「轮询等最终一致」而不是「同步等返回」。
 * MQ 依赖本地 9876（compose 的 note-mq-namesrv），测试上下文会启动全部消费者真实消费。
 */
class NoteMqE2ETest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private NoteImageMapper noteImageMapper;
    @Autowired
    private ImageProcessService imageProcessService;
    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * 延迟消息全链路：一篇卡在「审核中」的笔记 + 一条 1 秒延迟消息 = 自动转人工队列。
     * 直插 mapper 而不走发布接口：绕开 publish 事件（否则审核消费者会抢先把它流转掉）
     */
    @Test
    void review_timeout_delay_message_escalates_stuck_note() {
        Long noteId = insertNote("延迟消息测试", "卡在审核中的笔记", Note.STATUS_REVIEWING);

        // 直发延迟级别 1（= 1s）的超时检查消息 —— 消费者要等 1s 后才能收到
        // （带 delayLevel 的重载要 Spring Messaging Message 包装，见 NoteEventProducer 注释）
        rocketMQTemplate.syncSend(MqTopics.NOTE_REVIEW_TIMEOUT,
                org.springframework.messaging.support.MessageBuilder
                        .withPayload(new NotePublishEvent(noteId)).build(), 3000, 1);

        assertThat(awaitDbStatus(noteId, Note.STATUS_MANUAL_REVIEW, 5_000))
                .as("1s 延迟到达后，超时消费者应把笔记转人工(status=4)").isTrue();
    }

    /**
     * 消费幂等：同一 noteId 连续处理两次，第二次被 Redis SETNX 挡住（瞬时返回、URL 不双重处理）
     */
    @Test
    void image_process_is_idempotent() {
        Long noteId = insertNote("幂等测试", "重复消息不重复处理", Note.STATUS_PUBLISHED);
        insertImage(noteId, "https://cdn.shai.ai/idem.jpg", 0);

        long t1 = System.currentTimeMillis();
        imageProcessService.process(noteId);          // 第一次：真处理（含模拟压图 sleep）
        long first = System.currentTimeMillis() - t1;

        long t2 = System.currentTimeMillis();
        imageProcessService.process(noteId);          // 第二次：幂等命中，秒回
        long second = System.currentTimeMillis() - t2;

        assertThat(first).as("第一次应真实处理（含 sleep）").isGreaterThanOrEqualTo(150);
        assertThat(second).as("第二次应被 SETNX 挡住").isLessThan(100);

        String url = noteImageMapper.selectList(Wrappers.<NoteImage>lambdaQuery()
                .eq(NoteImage::getNoteId, noteId)).get(0).getUrl();
        assertThat(url).contains("w=800");
        assertThat(url).as("不能双重处理").doesNotContain("w=800&fmt=webp?");
    }

    /**
     * 故障隔离的活证明：标题带 poison 的笔记，图片消费者持续失败（进重试→死信），
     * 但审核消费者照常流转 —— 一个下游挂了不拖累另一个，这就是「一次发布、独立消费组」的价值。
     * （死信的最终归宿去控制台肉眼看：Topic 搜 %DLQ%note-image-group）
     */
    @Test
    void poison_note_review_ok_but_image_fails_independently() {
        String token = registerAndLogin();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        Map<String, Object> body = Map.of(
                "title", "poison 图片故障演练",
                "content", "审核应该正常通过",
                "images", List.of("https://cdn.shai.ai/p1.jpg", "https://cdn.shai.ai/p2.jpg"));
        ResponseEntity<Map> r = http.postForEntity(url("/api/notes"), new HttpEntity<>(body, headers), Map.class);
        assertThat(r.getBody().get("code")).isEqualTo(0);
        Long noteId = ((Number) ((Map<?, ?>) r.getBody().get("data")).get("id")).longValue();

        // 审核（review 组）不受图片故障影响 → 正常已发布
        assertThat(awaitDbStatus(noteId, Note.STATUS_PUBLISHED, 10_000))
                .as("图片消费者在疯狂失败，审核消费者应照常流转").isTrue();

        // 图片（image 组）第一次消费就抛异常进重试 —— 2s 内 URL 仍是原图
        sleep(2000);
        String img = noteImageMapper.selectList(Wrappers.<NoteImage>lambdaQuery()
                .eq(NoteImage::getNoteId, noteId)).get(0).getUrl();
        assertThat(img).as("图片处理失败重试中，URL 应保持原图").doesNotContain("w=800");
    }

    // ---------- 小工具 ----------
    // insertNote / sleep 已上提 AbstractIntegrationTest（LikeFlowE2ETest 也要用，三份重复不如一份公共）

    private void insertImage(Long noteId, String url, int sort) {
        NoteImage img = new NoteImage();
        img.setNoteId(noteId);
        img.setUrl(url);
        img.setSort(sort);
        noteImageMapper.insert(img);
    }

    /** 轮询数据库等状态到位（直查 mapper，绕过 HTTP 更贴近消费者的副作用） */
    private boolean awaitDbStatus(Long noteId, int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Note note = noteMapper.selectById(noteId);
            if (note != null && note.getStatus() == expected) {
                return true;
            }
            sleep(200);
        }
        return false;
    }

    private String registerAndLogin() {
        String username = "u_" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        http.postForEntity(url("/api/auth/register"),
                new HttpEntity<>(Map.of("username", username, "password", "pass123456", "nickname", "作者"), jsonHeaders), Map.class);
        ResponseEntity<Map> login = http.postForEntity(url("/api/auth/login"),
                new HttpEntity<>(Map.of("username", username, "password", "pass123456"), jsonHeaders), Map.class);
        return (String) ((Map<?, ?>) login.getBody().get("data")).get("accessToken");
    }
}

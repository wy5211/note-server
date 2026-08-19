package com.example.note.like;

import com.example.note.mq.MqTopics;
import com.example.note.mq.event.NoteLikeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

/**
 * 【V3】最终形态 = V2 的快 + MQ 的稳：
 *   同步部分：Redis 计数/位图（用户立刻看到数字 +1，体验拉满）
 *   异步部分：发一条点赞事件进 MQ → 消费者攒批、匀速落库（MySQL 彻底退出洪峰链路）
 *
 * 削峰填谷的直觉（水坝比喻）：
 *   V2 的问题是「洪峰直接冲向下游的每个环节」—— Redis 扛得住，但只要链路上还有一步慢的
 *   （以后点赞还要发通知、加积分、写分析……），洪峰就把那一步打挂。
 *   MQ = 水坝：进多少都先蓄着，下游按自己的节奏匀速放水。
 *
 * 降级：MQ 发送失败 → 退化成 V1 直写库（这次点赞会慢一点，但数据不丢）—— 复习 Phase 1 的降级思想
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MqLikeService {

    private final RedisLikeService redisLikeService;
    private final DbLikeService dbLikeService;
    private final RocketMQTemplate rocketMQTemplate;

    public void like(Long userId, Long noteId) {
        redisLikeService.like(userId, noteId);          // 同步：用户可感知的部分
        sendOrDegrade(new NoteLikeEvent(userId, noteId, true));
    }

    public void unlike(Long userId, Long noteId) {
        redisLikeService.unlike(userId, noteId);
        sendOrDegrade(new NoteLikeEvent(userId, noteId, false));
    }

    private void sendOrDegrade(NoteLikeEvent event) {
        try {
            // 点赞场景对「发送确认」的容忍度比发布高：异步发送也行，这里仍用同步发送保持教学一致
            rocketMQTemplate.syncSend(MqTopics.NOTE_LIKE, event);
        } catch (Exception e) {
            log.error("点赞事件发送失败，降级直写库 userId={} noteId={}", event.userId(), event.noteId(), e);
            if (event.like()) {
                dbLikeService.like(event.userId(), event.noteId());
            } else {
                dbLikeService.unlike(event.userId(), event.noteId());
            }
        }
    }
}

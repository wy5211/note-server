package com.example.note.note.mq;

import com.example.note.moderation.ReviewService;
import com.example.note.mq.MqTopics;
import com.example.note.mq.event.NotePublishEvent;
import com.example.note.note.service.ImageProcessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 笔记事件生产者 ≈ NestJS 里注入 Queue 并 queue.add('note-publish', { noteId })
 *
 * 两个必学的工程决策（面试高频，生产高频）：
 *
 * 【决策 1：事务提交后再发消息】
 *   ❌ 在 @Transactional 方法里直接发：消息飞得比事务提交快 —— 消费者收到消息来查库，
 *      行还不存在（事务未 commit），查了个寂寞；更糟的是重试也一样扑空。
 *   ✅ TransactionSynchronizationManager 注册「提交后回调」，commit 完成才发送。
 *   遗留问题：commit 后发送失败怎么办？消息丢了，笔记永远卡「审核中」——
 *   这里用【决策 2】先兜住，彻底解法（本地消息表/事务消息）是 Phase 6 的主角。
 *
 * 【决策 2：发送失败要降级】
 *   MQ 不是银弹，它会挂。syncSend 抛异常时不能装死：降级为「本地同步执行」，
 *   牺牲这一篇的响应速度，换取功能不坏。真实世界的降级链路：MQ → 本地 → 告警人工。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoteEventProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final ReviewService reviewService;
    private final ImageProcessService imageProcessService;

    /** 延迟级别：4 = 30s（application.yml，测试覆盖为 1 = 1s） */
    @Value("${note.mq.review-timeout-delay-level:4}")
    private int reviewTimeoutDelayLevel;

    /**
     * 发布事件的「事务提交后」发送入口。调用方在事务内调它，它只注册回调，不发消息。
     * （⚠️ 事务未激活时 registerSynchronization 会抛 IllegalStateException —— 所以只在
     *   确定有事务的 publish 流程里调）
     */
    public void publishAfterCommit(Long noteId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendPublishEvent(noteId);
            }
        });
    }

    /** 真正的发送 + 降级。包一层 public 是为了降级逻辑可被单独测试 */
    public void sendPublishEvent(Long noteId) {
        NotePublishEvent event = new NotePublishEvent(noteId);
        try {
            // syncSend 同步等 broker 确认（对照 Bull 的 add 是 fire-and-forget，这里更稳）
            rocketMQTemplate.syncSend(MqTopics.NOTE_PUBLISH, event);
            log.info("发布事件已发送 noteId={}", noteId);
        } catch (Exception e) {
            log.error("发布事件发送失败，降级为本地同步处理 noteId={}", noteId, e);
            degrade(noteId);
        }
        sendReviewTimeoutCheck(noteId);
    }

    /**
     * 延迟消息：N 秒后唤醒超时消费者检查这篇笔记是否卡在「审核中」。
     * 注意 API 形态：带 delayLevel 的 syncSend 重载收的是 Spring Messaging 的 Message
     * （rocketmq-spring 构建在 spring-messaging 抽象上，MessageBuilder 包装 payload）
     */
    private void sendReviewTimeoutCheck(Long noteId) {
        try {
            org.springframework.messaging.Message<NotePublishEvent> msg =
                    org.springframework.messaging.support.MessageBuilder
                            .withPayload(new NotePublishEvent(noteId)).build();
            rocketMQTemplate.syncSend(MqTopics.NOTE_REVIEW_TIMEOUT, msg, 3000, reviewTimeoutDelayLevel);
        } catch (Exception e) {
            // 超时检查丢了不至于功能坏（只是没有自动转人工），降级为告警日志
            log.error("审核超时消息发送失败（无人工兜底风险）noteId={}", noteId, e);
        }
    }

    /** 降级：本地同步跑一遍审核 + 图片处理（用户这次发布会慢，但功能完整） */
    void degrade(Long noteId) {
        reviewService.review(noteId);
        imageProcessService.process(noteId);
    }
}

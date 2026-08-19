package com.example.note.moderation;

import com.example.note.mq.MqTopics;
import com.example.note.mq.event.NotePublishEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 审核超时消费者 —— RocketMQ 杀手锏「延迟消息」的第一现场
 *
 * 产品场景：你发笔记后审核服务挂了/积压，笔记永远卡「审核中」，用户骂娘。
 * 方案对比（为什么用延迟消息）：
 *   ❌ 定时任务每分钟扫全表 status=1 —— 全表扫描、扫得太勤/太疏两难
 *   ✅ 发布时顺手发一条 30 分钟后才能被消费的消息，到点消费者自动醒来检查
 *      （NestJS 生态的等价物：Bull 的 delayed job / @nestjs/bull 的 delay 选项）
 *
 * RocketMQ 延迟实现：Broker 收到消息后不投递，先躺在特殊 topic 里按级别排队，
 * 到点搬运到真实 topic —— 所以只有 18 个固定级别（1s 5s 10s 30s 1m ... 2h），
 * 不能任意时间。任意时间延迟要 RocketMQ 5.x 的 Timer Wheel（可聊的进阶话题）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.NOTE_REVIEW_TIMEOUT, consumerGroup = MqTopics.REVIEW_TIMEOUT_GROUP)
public class ReviewTimeoutConsumer implements RocketMQListener<NotePublishEvent> {

    private final ReviewService reviewService;

    @Override
    public void onMessage(NotePublishEvent event) {
        reviewService.escalateIfStuck(event.noteId());
    }
}

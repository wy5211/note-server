package com.example.note.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 死信告警消费者 —— 消息的「太平间」看守
 *
 * 死信队列（DLQ）机制回顾：消费失败 → 进 %RETRY%{组} 按级别延迟重试（10s 30s 1m...）
 * → 重试 16 次仍失败 → 进 %DLQ%{组}，默认没人消费，躺着等 48h 被清理。
 *
 * 约定俗成：每个消费组都要有人管自己的 DLQ —— 真实世界这里接告警平台/工单/值班电话，
 * 教学版打 ERROR 日志。发一篇标题带 "poison" 的笔记，然后去控制台看它的遗骸：
 *   http://localhost:8180 → Topic → %RETRY%note-image-group / %DLQ%note-image-group
 *
 * 注意消息体：死信是原始消息的完整拷贝，所以这里 payload 类型和原消费者一致；
 * 但为通用性（一个 DLQ 消费者可能管多种消息）这里用 String 收原始体
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = MqTopics.IMAGE_DLQ, consumerGroup = "dlq-alarm-group")
public class DlqAlarmConsumer implements RocketMQListener<String> {

    @Override
    public void onMessage(String rawBody) {
        // 真实世界：值班电话 + 工单 + 把消息体存档供人工重放
        log.error("[死信告警] 图片处理重试 16 次仍失败，需人工介入 body={}", rawBody);
    }
}

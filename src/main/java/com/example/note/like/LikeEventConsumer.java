package com.example.note.like;

import com.example.note.like.LikeFlushService;
import com.example.note.mq.MqTopics;
import com.example.note.mq.event.NoteLikeEvent;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 点赞消费者：只做一件事 —— 把消息塞进攒批缓冲（微秒级），批量落库由定时任务负责
 *
 * 对照 Phase 1 的消费者（收到即处理）：高吞吐场景的消费者要「收得快、攒着办」。
 * consumeMessageBatchMaxSize 是另一条攒批路线（让 MQ 一次推一批消息），效果同向，
 * 这里用内存缓冲 + 定时 flush 的写法，因为它把「攒批节奏」的控制权握在自己手里
 */
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.NOTE_LIKE, consumerGroup = MqTopics.LIKE_GROUP)
public class LikeEventConsumer implements RocketMQListener<NoteLikeEvent> {

    private final LikeFlushService likeFlushService;

    @Override
    public void onMessage(NoteLikeEvent event) {
        likeFlushService.record(event);
    }
}

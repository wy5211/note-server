package com.example.note.feed;

import com.example.note.mq.MqTopics;
import com.example.note.mq.event.NotePublishEvent;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * Feed 写扩散消费者 —— Phase 1 承诺的兑现现场：
 *
 * 「以后加 ES 同步、积分奖励 = 新增消费组，发布方一行不改」——
 * 本类订阅的还是 note-publish（和审核组/图片组同一个 topic），新加第三个下游，
 * NoteService / NoteEventProducer 一个字没动。这就是事件驱动解耦的复利
 */
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.NOTE_PUBLISH, consumerGroup = MqTopics.FEED_GROUP)
public class FeedPushConsumer implements RocketMQListener<NotePublishEvent> {

    private final FeedPushService feedPushService;

    @Override
    public void onMessage(NotePublishEvent event) {
        feedPushService.pushToFollowers(event.noteId());
    }
}

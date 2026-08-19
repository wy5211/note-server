package com.example.note.note.mq;

import com.example.note.mq.MqTopics;
import com.example.note.mq.event.NotePublishEvent;
import com.example.note.note.service.ImageProcessService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 图片处理消费者：订阅 note-publish 的第二个消费组
 *
 * 和审核组的关系：各自独立的消费组 = 各拿一份全量消息、各自的进度、各自的重试。
 * 审核组挂了图片照处理，图片组重试审核不受影响 —— 「发布」这个业务动作的下游彻底解耦。
 */
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.NOTE_PUBLISH, consumerGroup = MqTopics.IMAGE_GROUP)
public class ImageProcessConsumer implements RocketMQListener<NotePublishEvent> {

    private final ImageProcessService imageProcessService;

    @Override
    public void onMessage(NotePublishEvent event) {
        imageProcessService.process(event.noteId());
    }
}

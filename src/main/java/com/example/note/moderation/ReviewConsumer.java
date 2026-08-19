package com.example.note.moderation;

import com.example.note.mq.MqTopics;
import com.example.note.mq.event.NotePublishEvent;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 审核消费者 ≈ NestJS/Bull 的 @Processor：
 *
 *   @Processor('note-publish')
 *   export class ReviewConsumer {
 *     @Process() async handle(job: Job<NotePublishEvent>) { await this.reviewService.review(job.data.noteId); }
 *   }
 *
 * Java 版：实现 RocketMQListener<T> + @RocketMQMessageListener 声明 topic/组。
 *
 * 消费确认模型对照（RocketMQ 没有「手动 ack」API）：
 *   onMessage 正常返回 = ACK（Bull 的 job.done()）
 *   抛异常 / 返回 RECONSUME_LATER = NACK，消息进重试队列（Bull 的 job.failed() + attempts）
 *
 * 「一次发布，多方消费」：图片组（note-image-group）订阅同一个 topic，
 * 审核挂了不影响图片处理，反之亦然 —— 这就是解耦的含金量
 */
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.NOTE_PUBLISH, consumerGroup = MqTopics.REVIEW_GROUP)
public class ReviewConsumer implements RocketMQListener<NotePublishEvent> {

    private final ReviewService reviewService;

    @Override
    public void onMessage(NotePublishEvent event) {
        reviewService.review(event.noteId());
    }
}

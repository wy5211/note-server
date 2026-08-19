package com.example.note.mq.event;

/**
 * 「笔记发布了」事件
 *
 * ⚠️ 消息体设计铁律：只发 ID，不发全量数据。
 *   反例：把 title/content/images 全塞进消息 —— 消息体大（浪费带宽）、
 *         消费者拿到的是「发送时刻的快照」（作者改过标题就消费到旧数据）、
 *         还可能泄漏敏感内容到日志。
 *   正解：消费者拿到 noteId 自己查库，永远处理最新状态。
 *   （NestJS 的 Bull job payload 同理：传引用，别传大对象）
 *
 * record + Jackson：rocketmq-spring 的消息转换器就是 Jackson，record 天然支持
 */
public record NotePublishEvent(Long noteId) {
}

package com.example.note.mq.event;

/**
 * 评论创建事件（顺序消息教学：按 noteId 进同一队列，保证楼层号分配有序）
 */
public record CommentCreatedEvent(Long noteId, Long commentId) {
}

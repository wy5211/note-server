package com.example.note.comment;

import com.example.note.comment.entity.Comment;
import com.example.note.comment.mapper.CommentMapper;
import com.example.note.mq.MqTopics;
import com.example.note.mq.event.CommentCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

/**
 * 评论服务 —— 顺序消息的发送端
 *
 * syncSendOrderly(topic, payload, hashKey)：按 hashKey 选队列 —— 同一 noteId 的评论
 * 永远进同一个队列（hash(noteId) % 队列数），队列内 FIFO —— 这是「局部有序」而非全局有序：
 * 不同笔记的评论互相之间无序也无所谓。业务上要什么序，就拿什么当 hashKey —— 这是关键
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentMapper commentMapper;
    private final RocketMQTemplate rocketMQTemplate;

    /** 发评论：入库（floor 待分配）→ 顺序消息 → 消费者按序编楼层 */
    public Long create(Long userId, Long noteId, String content) {
        Comment comment = new Comment();
        comment.setNoteId(noteId);
        comment.setUserId(userId);
        comment.setContent(content);
        comment.setLikeCount(0);
        commentMapper.insert(comment);

        // hashKey = noteId 字符串：同笔记同队列 —— 楼层分配的顺序前提
        rocketMQTemplate.syncSendOrderly(MqTopics.COMMENT_CREATE,
                new CommentCreatedEvent(noteId, comment.getId()), String.valueOf(noteId));
        log.debug("评论已入顺序队列 noteId={} commentId={}", noteId, comment.getId());
        return comment.getId();
    }
}

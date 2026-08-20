package com.example.note.comment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.comment.entity.Comment;
import com.example.note.comment.mapper.CommentMapper;
import com.example.note.mq.MqTopics;
import com.example.note.mq.event.CommentCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 评论消费者 —— 顺序消费（ORDERLY）：单队列单线程串行处理
 *
 * 楼层分配为什么必须有序：floor = max(floor)+1，两条同笔记的消息并发执行
 * 都读到「当前 3 楼」→ 都写 4 楼 → 楼层重复。顺序消费把「读-算-写」串行化。
 *
 * ORDERLY vs CONCURRENTLY 的代价：吞吐下降（放弃并行换顺序），且失败重试是
 * 「本地挂起重试」（阻塞当前队列）而不是进重试队列 —— 顺序场景失败的毒丸消息
 * 会卡住整个队列，需要更小心的失败处理（教学里直接抛，注释留档这个风险）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.COMMENT_CREATE,
        consumerGroup = MqTopics.COMMENT_GROUP,
        consumeMode = ConsumeMode.ORDERLY)
public class CommentConsumer implements RocketMQListener<CommentCreatedEvent> {

    private final CommentMapper commentMapper;

    @Override
    public void onMessage(CommentCreatedEvent event) {
        Comment comment = commentMapper.selectById(event.commentId());
        if (comment == null || comment.getFloor() != null) {
            return;   // 幂等：已删或已编过楼层（重复消息）
        }
        // 注意语法：泛型方法调用的显式类型参数要点出来 —— Wrappers.<Comment>lambdaQuery()
        // （少写这个点，javac 会把它解析成「Wrappers < Comment > lambdaQuery()」的数学表达式，
        //  报出「找不到符号: 变量 Wrappers」这种迷惑性极强的错 —— 本行真实踩过）
        Comment latest = commentMapper.selectOne(Wrappers.<Comment>lambdaQuery()
                .eq(Comment::getNoteId, event.noteId())
                .isNotNull(Comment::getFloor)
                .orderByDesc(Comment::getFloor)
                .last("LIMIT 1"));
        int maxFloor = latest == null ? 0 : latest.getFloor();
        comment.setFloor(maxFloor + 1);
        commentMapper.updateById(comment);
        log.debug("楼层分配 noteId={} commentId={} → {} 楼", event.noteId(), event.commentId(), comment.getFloor());
    }
}

package com.example.note.localmsg;

import com.example.note.localmsg.entity.LocalMessage;
import com.example.note.localmsg.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地消息表：方案一的「原子性载体」
 *
 * 用法铁律：enqueue 必须在业务事务内调用 —— 业务行和消息行同库同事务，
 * 要么都落要么都不落，「业务成功但消息没发出去」这个坑从物理上被消灭。
 * （Propagation.MANDATORY：调用方必须有事务，否则直接报错 —— 把铁律写进代码，
 *  谁在事务外乱调它立刻炸，编译不了运行时也跑不掉）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalMessageService {

    private final LocalMessageMapper localMessageMapper;

    /**
     * 业务事务内入队一条待发消息。
     * @param messageKey 幂等键：同一业务事件重复入队会被 uk 挡掉（INSERT IGNORE 语义）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String messageKey, String topic, String body) {
        LocalMessage msg = new LocalMessage();
        msg.setMessageKey(messageKey);
        msg.setTopic(topic);
        msg.setBody(body);
        msg.setStatus(LocalMessage.STATUS_PENDING);
        msg.setRetryCount(0);
        try {
            localMessageMapper.insert(msg);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.debug("消息已在队列（幂等）key={}", messageKey);
        }
    }
}

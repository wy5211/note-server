package com.example.note.localmsg;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.localmsg.entity.LocalMessage;
import com.example.note.localmsg.mapper.LocalMessageMapper;
import com.example.note.mq.MqTopics;
import com.example.note.mq.event.NotePublishEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息中继 Job：扫表 → 投递 MQ → 标记成功（方案一的「投递环节」）
 *
 * 方案一全景（本地消息表 = 最终一致的传家宝方案）：
 *   业务事务 { 审核通过 + 插 local_message }   ← 原子性：同库同事务
 *   中继任务 { 扫 PENDING → 发 MQ → 标 SENT }  ← 至少一次投递（可能重发，幂等靠下游）
 *   消费端   { 锁 + 流水幂等 }                  ← 恰好一次效果
 *
 * 失败重试：指数退避（2^retry 秒）—— 第 1 次失败 2s 后再试，第 5 次 32s……
 * 超过上限进「死信状态」（status=2）等人工 —— 和 MQ 死信队列同一个思想，只是落在表里
 *
 * 扫描-处理型任务 → 分布式锁（Phase 3 的结论再次上岗）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMessageRelayJob {

    private static final String LOCK_KEY = "note:job:local-msg-relay";
    private static final int BATCH = 100;
    private static final int MAX_RETRY = 10;

    private final LocalMessageMapper localMessageMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final RedissonClient redissonClient;

    @Value("${note.job.local-msg-relay-auto:true}")
    private boolean autoRelay;

    @Scheduled(fixedDelay = 5_000)
    public void scheduled() {
        if (!autoRelay) {
            return;
        }
        relayOnce();
    }

    /** 中继一轮（public 供测试/手动补跑） */
    public void relayOnce() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 60, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) {
                return;
            }
            doRelay();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doRelay() {
        List<LocalMessage> pendings = localMessageMapper.selectList(Wrappers.<LocalMessage>lambdaQuery()
                .eq(LocalMessage::getStatus, LocalMessage.STATUS_PENDING)
                .le(LocalMessage::getNextRetryAt, LocalDateTime.now())
                .orderByAsc(LocalMessage::getId)
                .last("LIMIT " + BATCH));
        if (pendings.isEmpty()) {
            return;
        }

        int sent = 0;
        int failed = 0;
        for (LocalMessage msg : pendings) {
            try {
                // 教学约定：point-reward 的消息体统一为 String(noteId) ——
                // 与事务消息路径（ReviewService 半消息）保持同一契约，消费端用一种姿势解析
                rocketMQTemplate.syncSend(MqTopics.POINT_REWARD,
                        MessageBuilder.withPayload(msg.getBody()).build());
                // 乐观标记：WHERE status=0 防止和别处并发标记（affected=0 也无所谓，等价成功）
                localMessageMapper.update(Wrappers.<LocalMessage>lambdaUpdate()
                        .eq(LocalMessage::getId, msg.getId())
                        .eq(LocalMessage::getStatus, LocalMessage.STATUS_PENDING)
                        .set(LocalMessage::getStatus, LocalMessage.STATUS_SENT));
                sent++;
            } catch (Exception e) {
                int retry = msg.getRetryCount() + 1;
                localMessageMapper.update(Wrappers.<LocalMessage>lambdaUpdate()
                        .eq(LocalMessage::getId, msg.getId())
                        .set(LocalMessage::getRetryCount, retry)
                        .set(LocalMessage::getNextRetryAt,
                                LocalDateTime.now().plusSeconds(1L << Math.min(retry, 16)))   // 指数退避
                        .set(LocalMessage::getStatus,
                                retry >= MAX_RETRY ? LocalMessage.STATUS_DEAD : LocalMessage.STATUS_PENDING));
                failed++;
                log.warn("消息投递失败第 {} 次 key={} err={}", retry, msg.getMessageKey(), e.getMessage());
            }
        }
        log.info("[中继] 本轮 {} 条：成功 {} 失败 {}", pendings.size(), sent, failed);
    }
}

package com.example.note.point;

import com.example.note.mq.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 奖励消费者（本地消息表 / 事务消息 两条路最终都汇到这里 —— 殊途同归于「下游幂等」）
 *
 * 消息契约：String(noteId) —— 两条生产路径（中继 syncSend / 半消息转正）统一，
 * 消费端一种姿势解析（事务消息回调不走转换器，String 是最稳的最大公约数）
 *
 * 锁的必要性：两条重复消息并发到达，都通过「流水不存在」检查再各自插入 → 双倍积分。
 * 分布式锁把同一 noteId 的消费串行化，让幂等检查真正可靠 ——
 * 锁 + 幂等检查 + 事务，是下游防重的完整三层
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.POINT_REWARD, consumerGroup = MqTopics.POINT_REWARD_GROUP)
public class PointRewardConsumer implements RocketMQListener<String> {

    private final PointService pointService;
    private final RedissonClient redissonClient;

    @Override
    public void onMessage(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D+", "");
        if (digits.isEmpty()) {
            log.error("[奖励] 无法解析消息体：{}", raw);
            return;
        }
        Long noteId = Long.parseLong(digits);

        RLock lock = redissonClient.getLock("point:reward:" + noteId);
        try {
            if (!lock.tryLock(3, 30, TimeUnit.SECONDS)) {
                // 抢锁失败说明另一条相同消息正在处理 —— 抛异常让本条进重试，稍后幂等命中
                throw new IllegalStateException("奖励处理锁竞争，进入重试 noteId=" + noteId);
            }
            pointService.awardPublishReward(noteId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}

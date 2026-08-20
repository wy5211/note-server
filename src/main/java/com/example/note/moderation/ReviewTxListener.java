package com.example.note.moderation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 事务消息监听器 —— 方案二的心脏（RocketMQ 半消息协议的三次握手）
 *
 * 完整时序（对照着记）：
 *   ① producer 发「半消息」：broker 收下但对消费者不可见
 *   ② broker 回调 executeLocalTransaction：执行本地事务（审核流转），返回
 *      COMMIT（半消息转正投递）/ ROLLBACK（丢弃）/ UNKNOWN（待定）
 *   ③ 若 ② 之后 producer 崩了没回话 —— broker 每隔约一分钟回调 checkLocalTransaction
 *      （默认最多 15 次）：查本地事务的最终状态（查库说话）
 *
 * 对照方案一的哲学差异：
 *   方案一把「待办消息」持久化在业务库（同事务），由中继任务投递 —— 一致性靠业务库
 *   方案二把「待定消息」托管给 broker（半消息），由回查对账 —— 一致性靠 MQ 协议
 *   两者殊途同归：至少一次投递 + 下游幂等 = 最终一致
 */
@Slf4j
@Component
@RocketMQTransactionListener
@RequiredArgsConstructor
public class ReviewTxListener implements RocketMQLocalTransactionListener {

    private final ReviewService reviewService;

    /** ② 半消息确认前的本地事务执行 */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        Long noteId = extractNoteId(msg);
        String decision = reviewService.reviewAndDecide(noteId);
        log.info("[事务消息] 本地事务执行完成 noteId={} → {}", noteId, decision);
        return toState(decision);
    }

    /** ③ 回查：producer 失联后的对账 —— 查库说话 */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        Long noteId = extractNoteId(msg);
        String decision = reviewService.checkDecision(noteId);
        log.info("[事务消息] 回查 noteId={} → {}", noteId, decision);
        return toState(decision);
    }

    /**
     * payload 契约：String(noteId)。事务消息回调不走普通转换器，payload 实际类型
     * 可能是 String 也可能是 byte[] —— 两种都接住（防御性解析，谁翻车都能定位）
     */
    private Long extractNoteId(Message msg) {
        Object payload = msg.getPayload();
        String raw;
        if (payload instanceof byte[] bytes) {
            raw = new String(bytes, StandardCharsets.UTF_8);
        } else {
            raw = String.valueOf(payload);
        }
        // 容忍 {"noteId":123} 形态（转换器可能给 JSON 字符串）
        String digits = raw.replaceAll("\\D+", "");
        return digits.isEmpty() ? null : Long.parseLong(digits);
    }

    private RocketMQLocalTransactionState toState(String decision) {
        return switch (decision) {
            case "COMMIT" -> RocketMQLocalTransactionState.COMMIT;
            case "ROLLBACK" -> RocketMQLocalTransactionState.ROLLBACK;
            default -> RocketMQLocalTransactionState.UNKNOWN;
        };
    }
}

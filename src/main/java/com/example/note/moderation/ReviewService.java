package com.example.note.moderation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.localmsg.LocalMessageService;
import com.example.note.mq.MqTopics;
import com.example.note.mq.event.NotePublishEvent;
import com.example.note.note.entity.Note;
import com.example.note.note.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审核核心 —— Phase 6 升级为「分布式事务的发起方」
 *
 * 业务流：审核通过(status→2) 时给作者发积分奖励。
 * 上游（审核）和下游（积分）之间隔着 MQ，本地事务管不过去 —— 两种业界标准方案共存：
 *
 *   note.reward.mode=local_msg（方案一·本地消息表）
 *     事务里 { 条件更新 status + 插 local_message } —— 同库同事务，业务成功消息必在
 *
 *   note.reward.mode=tx_msg（方案二·RocketMQ 事务消息）
 *     发半消息（对消费者不可见）→ listener 里执行本地审核 → COMMIT/ROLLBACK 半消息
 *     → broker 定时回查兜底（进程崩在中间也能被回查纠正）
 *
 * 两方案的详细对照见 README —— 这是「面试两连问」的完整答案
 *
 * 幂等方案 #1 温故：「条件更新天然幂等」（WHERE status=1），重复消费第二次 UPDATE
 * 影响 0 行 —— 本 Phase 两种分布式方案都靠它打底
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final NoteMapper noteMapper;
    private final SensitiveWordChecker checker;
    private final LocalMessageService localMessageService;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectProvider<ReviewService> selfProvider;

    @Value("${note.reward.mode:local_msg}")
    private String rewardMode;

    /**
     * 审核入口（消费者调用）—— 按配置走两条路之一
     */
    public void review(Long noteId) {
        if ("tx_msg".equals(rewardMode)) {
            // 方案二：半消息先行。本地事务挪到 ReviewTxListener.executeLocalTransaction 里做。
            // ⚠️ API 考察 1：sendMessageInTransaction 只收 Spring Message（无 payload 便捷重载）。
            // ⚠️ API 考察 2：事务消息的回调【不走普通消息转换器】—— listener 收到的 payload
            //    不是发送时的类型（实测拿到 byte[]），对象类型强转会当场炸掉。所以契约就用
            //    最朴素的 String（noteId 本身），两端零歧义 —— 和本地消息表的 body 约定一致
            rocketMQTemplate.sendMessageInTransaction(
                    MqTopics.POINT_REWARD,
                    org.springframework.messaging.support.MessageBuilder
                            .withPayload(String.valueOf(noteId)).build(),
                    null);
            return;
        }
        // 方案一：本地事务里做审核 + 落本地消息。
        // ⚠️ 必须走代理调（Phase 5 失效现场 1 的复习）：this. 直调 @Transactional 失效
        selfProvider.getObject().reviewWithLocalMessage(noteId);
    }

    /** 方案一的事务体：审核流转 + 消息入队，同生共死 */
    @Transactional
    public void reviewWithLocalMessage(Long noteId) {
        int target = decideAndApply(noteId);
        if (target == Note.STATUS_PUBLISHED) {
            // 与审核更新同一个事务 —— 「审核成功但奖励消息没落」在物理上不可能发生
            localMessageService.enqueue("reward:" + noteId, MqTopics.POINT_REWARD, String.valueOf(noteId));
        }
    }

    /**
     * 事务消息的本地事务体（ReviewTxListener 调用）：执行审核，返回半消息去向决策
     */
    public String reviewAndDecide(Long noteId) {
        int target = decideAndApply(noteId);
        if (target == Note.STATUS_PUBLISHED) {
            return "COMMIT";
        }
        if (target == Note.STATUS_REJECTED) {
            return "ROLLBACK";   // 驳回不发奖，半消息作废
        }
        return "UNKNOWN";        // 状态不明（人工审核中/已被并发处理）→ 让 broker 回查
    }

    /**
     * 回查兜底（ReviewTxListener 的 checkLocalTransaction 调用）：
     * 本地事务结果已落库 —— 直接查状态说话。这是事务消息「崩了也能自愈」的魔法来源
     */
    public String checkDecision(Long noteId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
            return "UNKNOWN";
        }
        return switch (note.getStatus()) {
            case Note.STATUS_PUBLISHED -> "COMMIT";
            case Note.STATUS_REJECTED -> "ROLLBACK";
            default -> "UNKNOWN";   // 还在审核/人工队列 → 下次回查再定
        };
    }

    /**
     * 超时兜底：延迟消息到达时笔记还卡「审核中」→ 转人工（status=4）
     */
    public void escalateIfStuck(Long noteId) {
        int affected = noteMapper.update(Wrappers.<Note>lambdaUpdate()
                .eq(Note::getId, noteId)
                .eq(Note::getStatus, Note.STATUS_REVIEWING)
                .set(Note::getStatus, Note.STATUS_MANUAL_REVIEW));
        if (affected > 0) {
            log.warn("笔记审核超时，已转入人工队列 noteId={}", noteId);
        }
    }

    // ---------- 私有 ----------

    /**
     * 决策 + 条件更新。返回最终生效的目标态；返回 -1 表示「没有发生流转」
     * （重复消息幂等命中 / 状态已变 —— 上游据此决定消息去向）
     */
    private int decideAndApply(Long noteId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
            log.warn("审核目标不存在 noteId={}", noteId);
            return -1;
        }
        if (note.getStatus() != Note.STATUS_REVIEWING) {
            log.debug("幂等命中：不在审核中 noteId={} status={}", noteId, note.getStatus());
            return -1;
        }
        int target = checker.isClean(note.getTitle(), note.getContent())
                ? Note.STATUS_PUBLISHED
                : Note.STATUS_REJECTED;
        int affected = noteMapper.update(Wrappers.<Note>lambdaUpdate()
                .eq(Note::getId, noteId)
                .eq(Note::getStatus, Note.STATUS_REVIEWING)
                .set(Note::getStatus, target));
        log.info("审核完成 noteId={} → {} affected={}", noteId, target, affected);
        return affected > 0 ? target : -1;
    }
}

package com.example.note.moderation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.note.entity.Note;
import com.example.note.note.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 审核核心逻辑（消费者和降级路径共用 —— 消费者只是 MQ 的薄壳，业务在 service）
 *
 * 幂等方案 #1（本项目用了两种，对照着学）：
 *   「条件更新天然幂等」—— UPDATE note SET status=? WHERE id=? AND status=1
 *   消息重复投递时第二次 UPDATE 影响 0 行，无任何副作用，不需要额外的去重存储。
 *   前提：业务是「状态流转」型。这是最优雅的幂等，能不用去重表就不用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final NoteMapper noteMapper;
    private final SensitiveWordChecker checker;

    /**
     * 审核一篇「审核中」的笔记：干净 → 2 已发布；脏 → 3 驳回。
     * 幂等：只有 status=1 才会被改，重复调用 / 并发调用都安全
     */
    public void review(Long noteId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
            // 容错：消息重试期间笔记可能已被作者删除 —— 记日志放弃，不能让消费者死循环
            log.warn("审核目标笔记不存在（可能已删除），跳过 noteId={}", noteId);
            return;
        }
        if (note.getStatus() != Note.STATUS_REVIEWING) {
            // 幂等命中：已被处理过（或已进人工队列），重复消息直接放过
            log.debug("幂等命中：笔记不在审核中，跳过 noteId={} status={}", noteId, note.getStatus());
            return;
        }

        int target = checker.isClean(note.getTitle(), note.getContent())
                ? Note.STATUS_PUBLISHED
                : Note.STATUS_REJECTED;

        // 条件更新：WHERE status=1 保证「只有第一个到的消费者能改」，其余全空转 —— 无锁而幂等
        int affected = noteMapper.update(Wrappers.<Note>lambdaUpdate()
                .eq(Note::getId, noteId)
                .eq(Note::getStatus, Note.STATUS_REVIEWING)
                .set(Note::getStatus, target));
        log.info("审核完成 noteId={} → status={} affected={}", noteId, target, affected);
    }

    /**
     * 超时兜底：延迟消息到达时笔记还卡在「审核中」→ 进人工队列（status=4）。
     * 幂等同理：条件更新只在 status=1 时生效
     */
    public void escalateIfStuck(Long noteId) {
        int affected = noteMapper.update(Wrappers.<Note>lambdaUpdate()
                .eq(Note::getId, noteId)
                .eq(Note::getStatus, Note.STATUS_REVIEWING)
                .set(Note::getStatus, Note.STATUS_MANUAL_REVIEW));
        if (affected > 0) {
            // 真实世界：这里发钉钉/飞书告警给审核组，或写进工单系统
            log.warn("笔记审核超时，已转入人工队列 noteId={}", noteId);
        } else {
            log.debug("超时检查：笔记已被正常处理，无需人工介入 noteId={}", noteId);
        }
    }
}

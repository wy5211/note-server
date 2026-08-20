package com.example.note.transaction;

import com.example.note.note.entity.Note;
import com.example.note.note.mapper.NoteMapper;
import com.example.note.transaction.entity.OperateLog;
import com.example.note.transaction.mapper.OperateLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务实战编排 —— 三个产品场景对应的传播行为选择
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionShowcaseService {

    private final NoteMapper noteMapper;
    private final OperateLogMapper operateLogMapper;
    private final OperateLogService operateLogService;

    /**
     * 场景一：发布失败要回滚，但审计日志必须留 —— REQUIRES_NEW
     * （跨 Bean 调用 operateLogService：经过代理，传播行为正确生效）
     */
    @Transactional
    public void publishAttemptFailedButAuditSurvives(Long userId, String title) {
        Note note = newNote(userId, title, "内容不重要");
        noteMapper.insert(note);

        // 独立事务先提交 —— 等下主事务回滚也带不走它
        operateLogService.logIndependently(userId, "PUBLISH_ATTEMPT",
                "尝试发布《" + title + "》—— 失败也要留痕");

        throw new IllegalStateException("模拟发布链路失败：主事务回滚");
    }

    /**
     * 场景二：一批操作里允许局部失败 —— NESTED 保存点
     * 外层的 note 存活，内层失败的日志回滚到保存点消失
     */
    @Transactional
    public void partialRollbackWithSavepoint(Long userId, String title) {
        Note note = newNote(userId, title, "外层写入，应存活");
        noteMapper.insert(note);

        try {
            operateLogService.nestedWorkThatFails(userId, "NESTED_FAIL");
        } catch (IllegalStateException e) {
            log.info("内层已回滚到保存点，外层继续：{}", e.getMessage());
        }

        operateLogMapper.insert(simpleLog(userId, "OUTER_DONE", "外层正常收尾"));
    }

    /**
     * 大事务回顾（Phase 0 病灶 → Phase 1 已重构，面试复述要点）：
     * 旧版 publish 的事务里含「同步审核 + sleep 压图」，事务持连接时间 = 整个链路，
     * 200 并发就把连接池（10 个）占满 —— 这就是「大事务」事故。
     * 重构原则：事务边界内只做 DB 写，一切慢操作（外部调用/IO/sleep）移出事务
     * 或移出请求（MQ）。判断口诀：「这个方法里最长的一步值不值得拖住连接」
     */

    private Note newNote(Long userId, String title, String content) {
        Note note = new Note();
        note.setUserId(userId);
        note.setTitle(title);
        note.setContent(content);
        note.setStatus(Note.STATUS_DRAFT);
        note.setLikeCount(0);
        note.setCollectCount(0);
        note.setCommentCount(0);
        note.setReadCount(0);
        return note;
    }

    private OperateLog simpleLog(Long userId, String action, String detail) {
        OperateLog l = new OperateLog();
        l.setUserId(userId);
        l.setAction(action);
        l.setDetail(detail);
        return l;
    }
}

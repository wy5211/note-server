package com.example.note.transaction;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.note.AbstractIntegrationTest;
import com.example.note.note.entity.Note;
import com.example.note.note.mapper.NoteMapper;
import com.example.note.transaction.entity.OperateLog;
import com.example.note.transaction.mapper.OperateLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 事务进阶验收：传播行为正确生效的证明 + 三大失效的实证
 *
 * 「失效测试」的断言方向是反的 —— 断言事务【没有】按注解宣称的行为工作，
 * 用测试钉死「这样写确实会翻车」
 */
class TransactionShowcaseTest extends AbstractIntegrationTest {

    @Autowired
    private TransactionShowcaseService showcase;
    @Autowired
    private InvalidTransactionalService invalid;
    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private OperateLogMapper operateLogMapper;

    // 数据隔离（踩坑档案 #14 的教训）：userId / 标题都带随机因子，
    // 绝对值断言不被上一轮运行残留的数据污染
    private long uniqueUserId() {
        return 9_000_000L + (System.nanoTime() % 1_000_000L);
    }

    @Test
    void requires_new_log_survives_main_rollback() {
        long userId = uniqueUserId();
        String title = "审计演示-" + userId;
        catchRun(() -> showcase.publishAttemptFailedButAuditSurvives(userId, title));

        // 主事务回滚：笔记消失
        assertThat(countNotesByTitle(title)).isZero();
        // REQUIRES_NEW：审计日志存活（这就是它存在的意义）
        assertThat(countLogs(userId, "PUBLISH_ATTEMPT")).isEqualTo(1);
    }

    @Test
    void nested_savepoint_rolls_back_only_inner_work() {
        long userId = uniqueUserId();
        String title = "保存点演示-" + userId;
        showcase.partialRollbackWithSavepoint(userId, title);

        assertThat(countNotesByTitle(title)).isEqualTo(1);               // 外层存活
        assertThat(countLogs(userId, "NESTED_FAIL")).isZero();           // 内层随保存点消失
        assertThat(countLogs(userId, "OUTER_DONE")).isEqualTo(1);        // 外层正常收尾
    }

    // ---------- 三大失效的实证 ----------

    @Test
    void failure1_self_invocation_kills_requires_new() {
        long userId = uniqueUserId();
        catchRun(() -> invalid.selfInvocationKillsRequiresNew(userId));

        // REQUIRES_NEW 被自调用绕过 → 日志被主事务回滚带走（正确用法下它应该活着）
        assertThat(countLogs(userId, "SHOULD_SURVIVE"))
                .as("自调用失效：REQUIRES_NEW 未生效，日志陪葬").isZero();
    }

    @Test
    void failure1_fix_proxy_invocation_saves_it() {
        long userId = uniqueUserId();
        catchRun(() -> invalid.proxyInvocationSavesRequiresNew(userId));

        // 经过代理（ObjectProvider 拿到的是代理对象）→ 传播行为恢复生效
        assertThat(countLogs(userId, "SHOULD_SURVIVE"))
                .as("代理调用：REQUIRES_NEW 生效，日志独立存活").isEqualTo(1);
    }

    @Test
    void failure2_swallowed_exception_commits_anyway() {
        long userId = uniqueUserId();
        String title = "本应回滚-" + userId;
        invalid.swallowExceptionCommitsAnyway(userId, title);

        assertThat(countNotesByTitle(title))
                .as("异常被 catch 吞掉：事务管理器不知情，照常提交（失效实证）").isEqualTo(1);
    }

    @Test
    void failure3_private_transactional_is_ignored() {
        long userId = uniqueUserId();
        String title = "私有方法-" + userId;
        catchRun(() -> invalid.triggerPrivateTransactional(userId, title));

        assertThat(countNotesByTitle(title))
                .as("private 方法：无法被子类重写，代理织不进事务（失效实证）").isEqualTo(1);
    }

    // ---------- 小工具 ----------

    private long countNotesByTitle(String title) {
        return noteMapper.selectCount(Wrappers.<Note>lambdaQuery().eq(Note::getTitle, title));
    }

    private long countLogs(Long userId, String action) {
        return operateLogMapper.selectCount(Wrappers.<OperateLog>lambdaQuery()
                .eq(OperateLog::getUserId, userId).eq(OperateLog::getAction, action));
    }

    private void catchRun(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception expected) {
            // 这些演示方法就是设计为抛异常的
        }
    }
}

package com.example.note.transaction;

import com.example.note.note.entity.Note;
import com.example.note.note.mapper.NoteMapper;
import com.example.note.transaction.entity.OperateLog;
import com.example.note.transaction.mapper.OperateLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @Transactional 三大失效现场（面试高频，全都用代码实证 + 测试断言「确实失效」）
 *
 * 根因只有一个：Spring 事务 = AOP 代理织入。绕过代理的一切路径都绕过了事务。
 *   失效 1：自调用（this.method()）—— 最常见
 *   失效 2：异常被 catch 吞掉（Spring 只看抛出来的异常决定回滚）
 *   失效 3：非 public 方法（代理拦截不到）
 */
@Service
@RequiredArgsConstructor
public class InvalidTransactionalService {

    private final NoteMapper noteMapper;
    private final OperateLogMapper operateLogMapper;

    /**
     * 注入「自己」：打破自调用失效的标准修法。
     * 用 ObjectProvider（延迟解析）而不是直接 @Autowired 自身字段 ——
     * 构造器注入自己会循环依赖（BeanCurrentlyInCreation），@Lazy 放字段上又不会
     * 被 Lombok 复制到构造参数，ObjectProvider 是零配置的正解：
     * 容器先把「供应商」给你，真正 getObject() 时 Bean 已经创建完，拿到的是代理
     */
    private final ObjectProvider<InvalidTransactionalService> selfProvider;

    private InvalidTransactionalService self() {
        return selfProvider.getObject();
    }

    /**
     * 【失效现场 1】自调用：this.logRequiresNew() 不走代理 → REQUIRES_NEW 根本没生效，
     * 日志加入的是当前事务 → 主事务回滚把「本应独立存活」的日志一起带走了。
     * 测试断言：日志不存在（正确用法下应该存在）—— 失效被实证
     */
    @Transactional
    public void selfInvocationKillsRequiresNew(Long userId) {
        noteMapper.insert(draftNote(userId, "自调用失效演示"));

        OperateLog log = new OperateLog();
        log.setUserId(userId);
        log.setAction("SHOULD_SURVIVE");
        log.setDetail("如果 REQUIRES_NEW 生效，我应该活下来");

        // ❌ this. 调用：编译器看是同一个对象直接调用，代理层完全不知情
        this.logViaRequiresNew(log);

        throw new IllegalStateException("主事务回滚");
    }

    /**
     * 【正确修法】用注入的 self（代理对象）调用 —— 同样的逻辑，事务行为恢复正确
     */
    @Transactional
    public void proxyInvocationSavesRequiresNew(Long userId) {
        noteMapper.insert(draftNote(userId, "代理调用正确演示"));

        OperateLog log = new OperateLog();
        log.setUserId(userId);
        log.setAction("SHOULD_SURVIVE");
        log.setDetail("经过代理调用，REQUIRES_NEW 真的开了独立事务");

        self().logViaRequiresNew(log);   // ✅ 代理调用：传播行为生效

        throw new IllegalStateException("主事务回滚");
    }

    /**
     * 【失效现场 2】异常被吞：insert 后 catch 住异常不抛 → Spring 认为一帆风顺 → 提交
     * （想要「捕获但回滚」得手动 TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()）
     */
    @Transactional
    public void swallowExceptionCommitsAnyway(Long userId, String title) {
        try {
            noteMapper.insert(draftNote(userId, title));
            throw new IllegalStateException("炸了");
        } catch (IllegalStateException ignored) {
            // 异常没抛出方法边界 —— 事务管理器看不见，照样提交
        }
    }

    /**
     * 【失效现场 3】private 方法：注解直接被无视 —— Spring 文档钦定的失效场景
     * （代理靠「子类重写方法」织入拦截，private 无法被重写 → 事务切面根本不生效）
     */
    @Transactional
    private void privateTransactionalIsIgnored(Long userId, String title) {
        noteMapper.insert(draftNote(userId, title));
        throw new IllegalStateException("这个异常应该触发回滚——但事务根本没开");
    }

    /** 失效 3 的触发入口（private 方法从外部只能这样够到） */
    public void triggerPrivateTransactional(Long userId, String title) {
        this.privateTransactionalIsIgnored(userId, title);   // 自调用 private —— 双重失效现场
    }

    /** 供自调用对比的方法：声明 REQUIRES_NEW，是否生效取决于「怎么被调」 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void logViaRequiresNew(OperateLog log) {
        operateLogMapper.insert(log);
    }

    private Note draftNote(Long userId, String title) {
        Note note = new Note();
        note.setUserId(userId);
        note.setTitle(title);
        note.setContent("c");
        note.setStatus(Note.STATUS_DRAFT);
        note.setLikeCount(0);
        note.setCollectCount(0);
        note.setCommentCount(0);
        note.setReadCount(0);
        return note;
    }
}

package com.example.note.transaction;

import com.example.note.transaction.entity.OperateLog;
import com.example.note.transaction.mapper.OperateLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 操作日志服务 —— 事务传播行为（Propagation）的正确用法示范
 *
 * 七种传播行为速查（记两常用 + 五冷门）：
 *   REQUIRED      默认：有事务就加入，没有就开一个
 *   REQUIRES_NEW  挂起当前事务，另起炉灶 —— 两笔事务互不影响（本类主秀）
 *   NESTED        当前事务内开保存点（SAVEPOINT），内层回滚只回到保存点，外层还能活
 *   SUPPORTS      有就加入、没有就裸跑（佛系）
 *   NOT_SUPPORTED 挂起当前事务，裸跑（事务嫌犯）
 *   MANDATORY     必须已有事务，否则报错（调用方的责任检查）
 *   NEVER         必须没有事务，否则报错
 *
 * ⚠️ 传播行为生效的前提：调用经过 Spring 代理（AOP）—— 所以这个类单独存在，
 * 让外层 Bean 调它（跨 Bean 调用走代理）。自调用会绕过代理 —— 失效演示见 InvalidTransactionalService
 */
@Service
@RequiredArgsConstructor
public class OperateLogService {

    private final OperateLogMapper operateLogMapper;

    /**
     * REQUIRES_NEW：无论调用方有没有事务，日志都在独立事务里提交。
     * 应用场景：审计日志 ——「发布失败可以回滚，但谁尝试过发布必须留痕」
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logIndependently(Long userId, String action, String detail) {
        OperateLog log = new OperateLog();
        log.setUserId(userId);
        log.setAction(action);
        log.setDetail(detail);
        operateLogMapper.insert(log);
        // 方法返回时这笔事务已独立提交 —— 哪怕调用方随后回滚，也带不走它
    }

    /**
     * NESTED：内层是外层的「保存点」。内层抛异常回到保存点（内层白干），
     * 外层捕获后继续，外层的写入照常提交 —— 「部分回滚」。
     * 与 REQUIRES_NEW 的区别：NESTED 仍是同一个物理事务（一个连接），
     * 外层最终回滚时内层也保不住；REQUIRES_NEW 是两个物理事务，彻底独立
     */
    @Transactional(propagation = Propagation.NESTED)
    public void nestedWorkThatFails(Long userId, String action) {
        OperateLog log = new OperateLog();
        log.setUserId(userId);
        log.setAction(action);
        log.setDetail("这条会随保存点一起消失");
        operateLogMapper.insert(log);
        throw new IllegalStateException("内层失败：触发回滚到保存点");
    }
}

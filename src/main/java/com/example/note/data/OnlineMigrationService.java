package com.example.note.data;

import com.example.note.transaction.OperateLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 【Phase 5 主戏之二】在线迁移编排 —— 手工版 gh-ost 全流程
 *
 * 六步流程（生产级工具 gh-ost/pt-osc 的本质就是这六步的自动化）：
 *   1. 建影子表 + 在影子上做真正的 DDL（影子无流量，随便改）
 *   2. 开双写：业务写原表时同步写影子（gh-ost 用 binlog 解析做增量，无需侵入代码 ——
 *      我们手工版侵入业务代码，这正是工具的价值）
 *   3. 存量拷贝：游标分批 INSERT SELECT（小事务 + 限流，和刷数同一套姿势）
 *   4. 校验：总量 COUNT + 抽样内容比对（数据工程的铁则：换表前必须验）
 *   5. 原子切换：RENAME TABLE a TO b, c TO a（一条命令，无中间态，毫秒级）
 *   6. 观察 + 清理：旧表留 24~48h 兜底再 DROP
 *
 * 每一步都留操作日志（REQUIRES_NEW 独立提交 —— 迁移审计恰恰是「业务回滚也不能丢」的典型，
 * Phase 5 事务块的学以致用）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineMigrationService {

    private static final int COPY_BATCH = 2000;
    private static final int SAMPLE_SIZE = 50;

    private final ShadowNoteMapper shadow;
    private final MigrationState migrationState;
    private final OperateLogService operateLogService;

    /** 一键全流程（教学演示入口；生产每一步是人工审批后单独执行的） */
    public void migrateNoteTitleColumn() {
        try {
            prepare();
            enableDualWrite();
            long copied = copyExistingData();
            boolean ok = verify(copied);
            if (!ok) {
                throw new IllegalStateException("校验不通过，中止切换（影子表保留供排查）");
            }
            atomicSwapAndFinish();
            log.info("[迁移] 全流程完成：note.title 已扩容为 VARCHAR(300)");
        } finally {
            migrationState.disableDualWrite();
        }
    }

    /** 步骤 1：影子表 + DDL */
    void prepare() {
        shadow.dropShadowIfAny();          // 教学可重复跑；生产这里要报警阻止二次迁移
        shadow.createShadowTable();
        shadow.applySchemaChange();
        step("PREPARE", "影子表已建并完成 DDL（title → VARCHAR(300)）");
    }

    /** 步骤 2：开双写（此后业务新写入两表同步） */
    void enableDualWrite() {
        migrationState.enableDualWrite();
        step("DUAL_WRITE_ON", "双写已开启：业务写入将同步进入影子表");
    }

    /** 步骤 3：存量拷贝（游标 + 限流） */
    long copyExistingData() {
        Long max = shadow.maxNoteId();
        long maxId = max == null ? 0 : max;   // 空表防御
        long copied = 0;
        for (long from = 0; from < maxId; from += COPY_BATCH) {
            long to = Math.min(from + COPY_BATCH, maxId);
            copied += shadow.copyRange(from, to);
            if (copied % 100_000 < COPY_BATCH) {
                log.info("[迁移] 存量拷贝进度 {}/{}", copied, maxId);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        step("COPY_DONE", "存量拷贝完成：" + copied + " 行");
        return copied;
    }

    /** 步骤 4：校验（总量 + 抽样内容） */
    boolean verify(long expectRows) {
        long noteCount = shadow.countOf("note");
        long shadowCount = shadow.countOf("note_shadow");
        if (noteCount != shadowCount) {
            log.error("[迁移] 数量不一致 note={} shadow={}", noteCount, shadowCount);
            return false;
        }
        // 抽样比对内容：随机取一批 id，两表的 title/content 必须逐行一致
        long maxId = shadow.maxNoteId();
        String ids = ThreadLocalRandom.current()
                .longs(SAMPLE_SIZE, 1, Math.max(maxId, 2))
                .distinct().mapToObj(String::valueOf)
                .collect(Collectors.joining(","));
        Map<Long, String> noteSample = shadow.sampleNote(ids).stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("id")).longValue(),
                        m -> m.get("title") + "|" + m.get("content")));
        Map<Long, String> shadowSample = shadow.sampleShadow(ids).stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("id")).longValue(),
                        m -> m.get("title") + "|" + m.get("content")));
        boolean same = noteSample.equals(shadowSample);
        step("VERIFY", same ? "校验通过（数量 " + noteCount + "，抽样 " + SAMPLE_SIZE + " 行一致）"
                : "校验失败！");
        return same;
    }

    /** 步骤 5+6：原子切换 + 清理 */
    void atomicSwapAndFinish() {
        shadow.atomicSwap();
        long len = shadow.titleColumnLength();   // 此时 note 已是新表
        shadow.dropOldTable();
        step("SWAP_DONE", "原子切换完成，title 列宽 = " + len);
    }

    private void step(String action, String detail) {
        log.info("[迁移] {} : {}", action, detail);
        // REQUIRES_NEW：迁移审计日志独立提交 —— 即使未来某个步骤在事务里失败回滚，迁移记录也不丢
        operateLogService.logIndependently(1L, "MIGRATION_" + action, detail);
    }
}
